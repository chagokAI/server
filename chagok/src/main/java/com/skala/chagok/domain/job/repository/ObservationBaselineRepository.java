package com.skala.chagok.domain.job.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Kafka 소비·DL 호출과 독립적으로, 이미 저장된 성공 관측만 집계한다. */
@Repository @RequiredArgsConstructor
public class ObservationBaselineRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public Map<UUID, Long> counts(UUID warehouseId, Instant from, Instant until) {
        Map<String, Object> params = Map.of("warehouseId", warehouseId, "from", Timestamp.from(from), "until", Timestamp.from(until));
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query("""
                select box_spec_id, count(*) as observations from item_event
                where warehouse_id = :warehouseId and status = 'SUCCEEDED' and box_spec_id is not null
                    and occurred_at >= :from and occurred_at < :until group by box_spec_id
                """, params, rs -> { counts.put(rs.getObject("box_spec_id", UUID.class), rs.getLong("observations")); });
        return counts;
    }
}
