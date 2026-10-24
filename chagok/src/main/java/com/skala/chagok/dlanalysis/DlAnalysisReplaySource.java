package com.skala.chagok.dlanalysis;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 이미 측정된 로컬 표본만 키셋 순서로 읽는다. 마지막 표본 뒤에는 처음으로 돌아간다. */
@Repository
@RequiredArgsConstructor
public class DlAnalysisReplaySource {
    private final NamedParameterJdbcTemplate jdbc;
    public record Cursor(Instant measuredAt, short run, String eventId) {}
    public record Sample(Cursor cursor, UUID warehouseId, String status, String barcode,
                         DlAnalysisResult.Size size, Long latencyMs) {}

    public Optional<Sample> next(Cursor after, UUID warehouseId) {
        var next = read(after, warehouseId);
        return next.isEmpty() && after != null ? read(null, warehouseId) : next;
    }

    private Optional<Sample> read(Cursor after, UUID warehouseId) {
        Map<String, Object> params = new HashMap<>();
        params.put("warehouse", warehouseId);
        String cursor = "";
        if (after != null) {
            params.put("at", Timestamp.from(after.measuredAt()));
            params.put("run", after.run());
            params.put("event", after.eventId());
            cursor = " and (s.measured_at,s.run,s.event_id) > (:at,:run,:event)";
        }
        return jdbc.query("""
                select s.*, e.warehouse_id from dl_measurement_sample s
                join item_event e on e.event_id=s.event_id
                where e.warehouse_id=:warehouse
                """ + cursor + " order by s.measured_at,s.run,s.event_id limit 1", params, (rs, i) -> new Sample(
                new Cursor(rs.getTimestamp("measured_at").toInstant(), rs.getShort("run"), rs.getString("event_id")),
                rs.getObject("warehouse_id", UUID.class), rs.getString("dl_status"), rs.getString("dl_barcode"),
                new DlAnalysisResult.Size(rs.getString("size_type"), number(rs.getObject("width_cm")),
                        number(rs.getObject("depth_cm")), number(rs.getObject("height_cm")), number(rs.getObject("volume_cm3"))),
                rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms")))
                .stream().findFirst();
    }

    private static Double number(Object value) { return value == null ? null : ((Number) value).doubleValue(); }
}
