package com.skala.chagok.domain.job.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 현재 기본 규격을 기준으로 발주 참고 수요를 집계한다. 실제 포장 사용량은 inventory_movement 원장이 맡는다.
 *
 * <p>기존 성공 관측 집계는 실제 배정 박스를 우선했다. 재고 차감 도입 후에는 품절로 대체 배정되거나 대기한 건이
 * 원래 필요한 규격의 수요에서 빠지므로, 발주 계산이 이 수요 집계로 옮겨 가면서 그 집계를 제거했다.
 */
@Repository @RequiredArgsConstructor
public class ObservationBaselineRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public record Baseline(Map<UUID, Long> counts, long observedBoxCount, long estimatedBoxCount) {}

    /** 일시 품절·대체 포장 때문에 원래 필요한 규격의 발주 수요가 줄지 않도록 한다. */
    public Baseline demand(UUID warehouseId, Instant from, Instant until) {
        return aggregate(warehouseId, from, until, """
                select b.id as box_spec_id, count(*) as observations,
                    count(*) filter (where ideal.id is null) as observed,
                    count(*) filter (where ideal.id is not null) as estimated
                from item_event e
                left join product p on p.id = e.product_id and p.warehouse_id = e.warehouse_id and p.is_active = true
                left join box_spec ideal on ideal.id = p.matched_box_spec_id
                    and ideal.warehouse_id = e.warehouse_id and ideal.is_active = true
                join box_spec b on b.id = coalesce(ideal.id, e.box_spec_id)
                    and b.warehouse_id = e.warehouse_id and b.is_active = true
                where e.warehouse_id = :warehouseId
                    and (e.status = 'SUCCEEDED' or (e.status = 'WAITING' and e.measurement->>'dlStatus' = 'known'))
                    and e.occurred_at >= :from and e.occurred_at < :until group by b.id
                """);
    }

    private Baseline aggregate(UUID warehouseId, Instant from, Instant until, String query) {
        Map<String, Object> params = Map.of("warehouseId", warehouseId, "from", Timestamp.from(from), "until", Timestamp.from(until));
        Map<UUID, Long> counts = new HashMap<>();
        long[] sources = {0, 0};
        jdbc.query(query, params, rs -> {
            counts.put(rs.getObject("box_spec_id", UUID.class), rs.getLong("observations"));
            sources[0] += rs.getLong("observed");
            sources[1] += rs.getLong("estimated");
        });
        return new Baseline(Map.copyOf(counts), sources[0], sources[1]);
    }
}
