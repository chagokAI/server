package com.skala.chagok.job;

import com.skala.chagok.domain.job.repository.ObservationBaselineRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class ObservationBaselineRepositoryTests {
    private JdbcTemplate jdbc;
    private ObservationBaselineRepository repository;
    private final UUID warehouse = UUID.randomUUID(), otherWarehouse = UUID.randomUUID();
    private final UUID observed = UUID.randomUUID(), matched = UUID.randomUUID(), inactive = UUID.randomUUID();
    private final UUID product = UUID.randomUUID();
    private final Instant from = Instant.parse("2026-10-01T00:00:00Z"), until = from.plusSeconds(86400);

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL", "sa", "");
        // JdbcTemplate opens a new connection for every operation.
        source.setUrl(source.getUrl() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        repository = new ObservationBaselineRepository(new NamedParameterJdbcTemplate(source));
        jdbc.execute("create table box_spec (id uuid primary key, warehouse_id uuid, is_active boolean)");
        jdbc.execute("create table product (id uuid primary key, warehouse_id uuid, matched_box_spec_id uuid, is_active boolean)");
        jdbc.execute("create table item_event (event_id varchar, warehouse_id uuid, product_id uuid, box_spec_id uuid, status varchar, occurred_at timestamp with time zone)");
        jdbc.update("insert into box_spec values (?, ?, true), (?, ?, true), (?, ?, false)", observed, warehouse, matched, warehouse, inactive, warehouse);
        jdbc.update("insert into product values (?, ?, ?, true)", product, warehouse, matched);
    }

    private void event(UUID wh, UUID p, UUID box, String status, Instant at) {
        jdbc.update("insert into item_event values (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), wh, p, box, status, Timestamp.from(at));
    }

    @Test void preservesObservedBoxAndEstimatesOnlyMissingBoxWithoutRewritingEvents() {
        event(warehouse, product, observed, "SUCCEEDED", from);
        event(warehouse, product, null, "SUCCEEDED", from.plusSeconds(1));
        event(warehouse, null, observed, "SUCCEEDED", from.plusSeconds(2));
        var result = repository.baseline(warehouse, from, until);
        assertThat(result.counts()).containsEntry(observed, 2L).containsEntry(matched, 1L);
        assertThat(result.observedBoxCount()).isEqualTo(2);
        assertThat(result.estimatedBoxCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from item_event where box_spec_id is null", Integer.class)).isEqualTo(1);
    }

    @Test void excludesFailedOutOfPeriodInactiveAndCrossWarehouseData() {
        event(warehouse, product, null, "FAILED", from);
        event(warehouse, product, null, "SUCCEEDED", from.minusSeconds(1));
        event(warehouse, product, null, "SUCCEEDED", until);
        event(warehouse, product, inactive, "SUCCEEDED", from);
        event(otherWarehouse, product, null, "SUCCEEDED", from);
        var foreign = UUID.randomUUID();
        jdbc.update("insert into product values (?, ?, ?, true)", foreign, otherWarehouse, matched);
        event(warehouse, foreign, null, "SUCCEEDED", from);
        var disabled = UUID.randomUUID();
        jdbc.update("insert into product values (?, ?, ?, false)", disabled, warehouse, matched);
        event(warehouse, disabled, null, "SUCCEEDED", from);
        event(warehouse, null, null, "SUCCEEDED", from);
        var result = repository.baseline(warehouse, from, until);
        assertThat(result.counts()).isEmpty();
        assertThat(result.observedBoxCount()).isZero();
        assertThat(result.estimatedBoxCount()).isZero();
    }
}
