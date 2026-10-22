package com.skala.chagok.domain.dashboard.repository;

import com.skala.chagok.domain.dashboard.dto.SceneResponse;
import java.math.RoundingMode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 복원된 DL 표본은 읽기만 한다. 이벤트 ID로 연결하고 cm를 화면 계약의 mm로 변환한다. */
@Repository @RequiredArgsConstructor
public class SimulationMeasurementRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public Map<String, SceneResponse.Measurement> findAll(Collection<String> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        Map<String, SceneResponse.Measurement> result = new HashMap<>();
        jdbc.query("""
                select event_id, dl_status, size_type, width_cm, depth_cm, height_cm, latency_ms, measured_at, failure_reason
                from dl_measurement_sample where event_id in (:ids) order by measured_at desc, run desc
                """, Map.of("ids", eventIds), rs -> {
            var at = rs.getTimestamp("measured_at");
            result.putIfAbsent(rs.getString("event_id"), new SceneResponse.Measurement(rs.getString("dl_status"),
                    rs.getString("size_type"), mm(rs.getBigDecimal("width_cm")), mm(rs.getBigDecimal("depth_cm")),
                    mm(rs.getBigDecimal("height_cm")), rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms"),
                    at == null ? null : at.toInstant(), rs.getString("failure_reason"), "IMPORTED_SAMPLE"));
        });
        return result;
    }

    public SceneResponse.AvailableRange range(UUID warehouseId) {
        return jdbc.queryForObject("""
                select min(e.occurred_at) as first_at, max(e.occurred_at) as last_at,
                    min(e.occurred_at) filter (where m.event_id is not null or e.measurement->>'widthMm' is not null) as first_measured,
                    max(e.occurred_at) filter (where m.event_id is not null or e.measurement->>'widthMm' is not null) as last_measured,
                    count(*) as total,
                    count(*) filter (where m.event_id is not null or e.measurement->>'widthMm' is not null) as measured
                from item_event e left join (select distinct event_id from dl_measurement_sample
                    where width_cm > 0 and depth_cm > 0 and height_cm > 0) m on m.event_id = e.event_id
                where e.warehouse_id = :warehouseId
                """, Map.of("warehouseId", warehouseId), (rs, row) -> new SceneResponse.AvailableRange(
                rs.getTimestamp("first_at") == null ? null : rs.getTimestamp("first_at").toInstant(),
                rs.getTimestamp("last_at") == null ? null : rs.getTimestamp("last_at").toInstant(),
                rs.getTimestamp("first_measured") == null ? null : rs.getTimestamp("first_measured").toInstant(),
                rs.getTimestamp("last_measured") == null ? null : rs.getTimestamp("last_measured").toInstant(),
                rs.getLong("total"), rs.getLong("measured")));
    }

    private static Integer mm(java.math.BigDecimal cm) {
        return cm == null || cm.signum() <= 0 ? null : cm.movePointRight(1).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
