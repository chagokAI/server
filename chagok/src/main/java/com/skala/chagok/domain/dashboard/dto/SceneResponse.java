package com.skala.chagok.domain.dashboard.dto;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.event.entity.ItemEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record SceneResponse(List<Observation> items, int limit, Instant latestObservedAt, int pendingEventCount,
                            Cursor nextCursor, boolean hasMore, AvailableRange availableRange) {
    public SceneResponse(List<Observation> items, int limit, Instant latestObservedAt, int pendingEventCount) {
        this(items, limit, latestObservedAt, pendingEventCount, null, false, null);
    }
    public record Cursor(Instant observedAt, String eventId) {}
    public record AvailableRange(Instant firstObservedAt, Instant lastObservedAt,
                                 Instant firstMeasuredObservedAt, Instant lastMeasuredObservedAt,
                                 long eventCount, long measuredEventCount) {}
    public record Measurement(String dlStatus, String sizeType, Integer widthMm, Integer depthMm, Integer heightMm,
                               Long latencyMs, Instant measuredAt, String failureReason, String source) {
        public static Measurement from(Map<String, Object> data) {
            if (data == null || data.isEmpty()) return null;
            Object at = data.get("measuredAt");
            Instant measured = null;
            try { if (at != null) measured = Instant.parse(at.toString()); } catch (RuntimeException ignored) { }
            return new Measurement((String) data.get("dlStatus"), (String) data.get("sizeType"), integer(data.get("widthMm")),
                    integer(data.get("depthMm")), integer(data.get("heightMm")), number(data.get("latencyMs")),
                    measured, (String) data.get("failureReason"), "WORKER");
        }
        private static Integer integer(Object value) { return value instanceof Number number ? number.intValue() : null; }
        private static Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
    }
    public record Dimensions(Integer widthMm, Integer depthMm, Integer heightMm) {}
    /** DL 측정값(mm). 등록 물품은 measured(라벨 실측), 처음 보는 물품은 estimated(모델 추정). 측정이 없으면 null */
    public record Measurement(String dlStatus, String sizeType, Integer widthMm, Integer depthMm, Integer heightMm,
                              Integer latencyMs, String failureReason) {}
    public record ProductInfo(UUID id, String code, String name, String imageUrl, Dimensions dimensions) {}
    public record Observation(String boxId, String eventId, Instant observedAt, String status, ProductInfo product,
                              BoxSnapshot boxSpec, BigDecimal fillRatePct, Map<String, Object> placement,
                              boolean placementAvailable, String dimensionsSource, Measurement measurement) {
        public Observation(String boxId, String eventId, Instant observedAt, String status, ProductInfo product,
                           BoxSnapshot boxSpec, BigDecimal fillRatePct, Map<String, Object> placement,
                           boolean placementAvailable, String dimensionsSource) {
            this(boxId, eventId, observedAt, status, product, boxSpec, fillRatePct, placement, placementAvailable, dimensionsSource, null);
        }
        public Observation withMeasurement(Measurement value) {
            return new Observation(boxId, eventId, observedAt, status, product, boxSpec, fillRatePct, placement,
                    placementAvailable, value != null && value.widthMm() != null ? "DL_" + Objects.toString(value.sizeType(), "UNKNOWN").toUpperCase(Locale.ROOT) : dimensionsSource, value);
        }
        public static Observation from(ItemEvent event) {
            return from(event, null);
        }

        public static Observation from(ItemEvent event, Measurement measurement) {
            var product = event.getProduct();
            ProductInfo info = product == null ? null : new ProductInfo(product.getId(), product.getCode(),
                    product.getName(), product.getImageUrl(),
                    new Dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm()));
            return new Observation(event.getId().getEventId(), event.getId().getEventId(), event.getId().getOccurredAt(),
                    event.getStatus(), info, BoxSnapshot.from(event.getBoxSpec()), event.getFillRatePct(),
                    event.getPlacement(), event.getPlacement() != null && !event.getPlacement().isEmpty(), "CATALOG")
                    .withMeasurement(Measurement.from(event.getMeasurement()));
                    event.getPlacement(), event.getPlacement() != null && !event.getPlacement().isEmpty(), "CATALOG", measurement);
        }
    }
}
