package com.skala.chagok.domain.dashboard.service;

import com.skala.chagok.domain.dashboard.dto.*;
import com.skala.chagok.domain.dashboard.repository.MetricQueryRepository;
import com.skala.chagok.domain.event.repository.ItemEventRepository;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ObservationQueryService {
    private final ItemEventRepository events;
    private final MetricQueryRepository metrics;
    private final BoxSpecRepository boxes;
    private final WarehouseQueryGuard warehouses;
    private final ChagokProperties properties;
    private final com.skala.chagok.domain.dashboard.repository.MeasurementQueryRepository measurements;

    public SceneResponse scene(UUID warehouseId, int limit) {
        warehouses.require(warehouseId);
        if (limit < 1 || limit > 500) throw BusinessException.invalidField("limit", "1~500 범위여야 합니다.");
        return response(warehouseId, limit, events.findRecent(warehouseId, PageRequest.of(0, limit)), true);
    }

    /** 기간 [from, to) 의 이벤트를 시간순으로(디지털트윈 재생용). 최신 시각은 마지막 항목 */
    public SceneResponse scene(UUID warehouseId, int limit, Instant from, Instant to) {
        warehouses.require(warehouseId);
        if (limit < 1 || limit > 500) throw BusinessException.invalidField("limit", "1~500 범위여야 합니다.");
        if (from == null || to == null || !from.isBefore(to))
            throw BusinessException.invalidField("from", "from 과 to 를 함께, from 이 to 보다 앞서게 지정하세요.");
        return response(warehouseId, limit, events.findInRange(warehouseId, from, to, PageRequest.of(0, limit)), false);
    }

    private SceneResponse response(UUID warehouseId, int limit, List<com.skala.chagok.domain.event.entity.ItemEvent> rows,
                                   boolean newestFirst) {
        var dl = measurements.byEventIds(rows.stream().map(e -> e.getId().getEventId()).toList());
        var items = rows.stream().map(e -> SceneResponse.Observation.from(e, dl.get(e.getId().getEventId()))).toList();
        Instant latest = items.isEmpty() ? null : (newestFirst ? items.getFirst() : items.getLast()).observedAt();
        return new SceneResponse(items, limit, latest, events.countByWarehouseIdAndStatus(warehouseId, "PENDING"));
    }

    public MetricSeries metrics(UUID warehouseId, MetricSeries.Metric metric, LocalDate from, LocalDate to,
                                 MetricSeries.Bucket bucket, UUID boxSpecId) {
        warehouses.require(warehouseId);
        QueryValues.dateRange(from, to);
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > (bucket == MetricSeries.Bucket.hour ? 31 : 366))
            throw BusinessException.invalidField("to", "시간별 조회는 최대 31일, 일별 조회는 최대 366일입니다.");
        if (boxSpecId != null && boxes.findByIdAndWarehouseId(boxSpecId, warehouseId).isEmpty())
            throw BusinessException.notFound("박스 규격");
        ZoneId zone = properties.timezone();
        ZonedDateTime start = from.atStartOfDay(zone), end = to.plusDays(1).atStartOfDay(zone);
        Map<Instant, MetricQueryRepository.Aggregate> values = metrics.aggregate(warehouseId, start.toInstant(),
                end.toInstant(), zone.getId(), bucket, metric, boxSpecId).stream()
                .collect(Collectors.toMap(MetricQueryRepository.Aggregate::at, value -> value));
        List<MetricSeries.Point> points = new ArrayList<>();
        for (ZonedDateTime at = start; at.isBefore(end); at = bucket == MetricSeries.Bucket.hour ? at.plusHours(1) : at.plusDays(1)) {
            var value = values.get(at.toInstant());
            BigDecimal missing = metric == MetricSeries.Metric.AVG_FILL_RATE_PCT ? null : BigDecimal.ZERO;
            points.add(new MetricSeries.Point(at.toOffsetDateTime(), value == null ? missing : value.value(),
                    value == null ? 0 : value.sampleCount()));
        }
        return new MetricSeries(metric, metric == MetricSeries.Metric.AVG_FILL_RATE_PCT ? "PERCENT" : "COUNT",
                from, to, bucket, zone.getId(), boxSpecId, points);
    }
}
