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

    public record Baseline(Map<UUID, Long> counts, long observedBoxCount, long estimatedBoxCount) {}

    /** 박스가 없는 레거시 관측만 현재 상품 매칭으로 추정한다. 실제 관측 박스는 항상 우선하며 원본은 수정하지 않는다. */
    public Baseline baseline(UUID warehouseId, Instant from, Instant until) {
        Map<String, Object> params = Map.of("warehouseId", warehouseId, "from", Timestamp.from(from), "until", Timestamp.from(until));
        Map<UUID, Long> counts = new HashMap<>();
        long[] sources = {0, 0};
        jdbc.query("""
                select b.id as box_spec_id, count(*) as observations,
                    count(*) filter (where e.box_spec_id is not null) as observed,
                    count(*) filter (where e.box_spec_id is null) as estimated
                from item_event e
                left join product p on p.id = e.product_id and p.warehouse_id = e.warehouse_id and p.is_active = true
                join box_spec b on b.id = coalesce(e.box_spec_id, p.matched_box_spec_id)
                    and b.warehouse_id = e.warehouse_id and b.is_active = true
                where e.warehouse_id = :warehouseId and e.status = 'SUCCEEDED'
                    and e.occurred_at >= :from and e.occurred_at < :until group by b.id
                """, params, rs -> {
            counts.put(rs.getObject("box_spec_id", UUID.class), rs.getLong("observations"));
            sources[0] += rs.getLong("observed");
            sources[1] += rs.getLong("estimated");
        });
        return new Baseline(Map.copyOf(counts), sources[0], sources[1]);
    }
}
