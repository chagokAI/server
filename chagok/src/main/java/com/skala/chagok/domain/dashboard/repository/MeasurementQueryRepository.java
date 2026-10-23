package com.skala.chagok.domain.dashboard.repository;

import com.skala.chagok.domain.dashboard.dto.SceneResponse.Measurement;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * DL 측정값(dl_measurement_sample, 엔티티 없는 공유 테이블)을 이벤트 id 로 붙인다.
 * 테이블이 없는 DB 에서는 빈 결과를 돌려준다.
 */
@Repository @RequiredArgsConstructor
public class MeasurementQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public Map<String, Measurement> byEventIds(Collection<String> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        Boolean exists = jdbc.queryForObject("select to_regclass('public.dl_measurement_sample') is not null", Map.of(), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) return Map.of();
        Map<String, Measurement> out = new HashMap<>();
        jdbc.query("select event_id, dl_status, size_type, width_cm, depth_cm, height_cm, latency_ms, failure_reason "
                + "from dl_measurement_sample where event_id in (:ids)", Map.of("ids", eventIds), rs -> {
            out.put(rs.getString("event_id"), new Measurement(rs.getString("dl_status"), rs.getString("size_type"),
                    mm(rs.getBigDecimal("width_cm")), mm(rs.getBigDecimal("depth_cm")), mm(rs.getBigDecimal("height_cm")),
                    rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms"),
                    null, rs.getString("failure_reason"), "IMPORTED_SAMPLE"));
        });
        return out;
    }

    private static Integer mm(java.math.BigDecimal cm) {
        return cm == null ? null : cm.movePointRight(1).setScale(0, java.math.RoundingMode.HALF_UP).intValue();
    }
}
