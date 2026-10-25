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

/**
 * 이미 측정된 로컬 표본만 키셋 순서로 읽는다. 마지막 표본 뒤에는 처음으로 돌아간다.
 * 센터는 표본의 warehouse_id 로 고른다. 비어 있는 예전 표본만 event_id 로 출고 원본을 찾아 센터를 확인한다.
 */
@Repository
@RequiredArgsConstructor
public class DlAnalysisReplaySource {
    private final NamedParameterJdbcTemplate jdbc;
    public record Cursor(Instant measuredAt, short run, String eventId) {}
    public record Sample(Cursor cursor, UUID warehouseId, String status, String barcode,
                         DlAnalysisResult.Size size, Long latencyMs) {}

    /** 표본이 센터에 직접 기록된 센터(연속 재생 대상) */
    public java.util.List<UUID> warehousesWithSamples() {
        return jdbc.query("select distinct warehouse_id from dl_measurement_sample where warehouse_id is not null",
                (rs, i) -> rs.getObject(1, UUID.class));
    }

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
                select s.* from dl_measurement_sample s
                where (s.warehouse_id=:warehouse or (s.warehouse_id is null and exists (
                    select 1 from item_event e where e.event_id=s.event_id and e.warehouse_id=:warehouse)))
                """ + cursor + " order by s.measured_at,s.run,s.event_id limit 1", params, (rs, i) -> new Sample(
                new Cursor(rs.getTimestamp("measured_at").toInstant(), rs.getShort("run"), rs.getString("event_id")),
                warehouseId, rs.getString("dl_status"), rs.getString("dl_barcode"),
                new DlAnalysisResult.Size(rs.getString("size_type"), number(rs.getObject("width_cm")),
                        number(rs.getObject("depth_cm")), number(rs.getObject("height_cm")), number(rs.getObject("volume_cm3"))),
                rs.getObject("latency_ms") == null ? null : rs.getLong("latency_ms")))
                .stream().findFirst();
    }

    private static Double number(Object value) { return value == null ? null : ((Number) value).doubleValue(); }
}
