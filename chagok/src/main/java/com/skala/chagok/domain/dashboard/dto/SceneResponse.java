package com.skala.chagok.domain.dashboard.dto;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.event.entity.ItemEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record SceneResponse(List<Observation> items, int limit, Instant latestObservedAt, int pendingEventCount) {
    public record Dimensions(Integer widthMm, Integer depthMm, Integer heightMm) {}
    public record ProductInfo(UUID id, String code, String name, String imageUrl, Dimensions dimensions) {}
    public record Observation(String boxId, String eventId, Instant observedAt, String status, ProductInfo product,
                              BoxSnapshot boxSpec, BigDecimal fillRatePct, Map<String, Object> placement,
                              boolean placementAvailable, String dimensionsSource) {
        public static Observation from(ItemEvent event) {
            var product = event.getProduct();
            ProductInfo info = product == null ? null : new ProductInfo(product.getId(), product.getCode(),
                    product.getName(), product.getImageUrl(),
                    new Dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm()));
            return new Observation(event.getId().getEventId(), event.getId().getEventId(), event.getId().getOccurredAt(),
                    event.getStatus(), info, BoxSnapshot.from(event.getBoxSpec()), event.getFillRatePct(),
                    event.getPlacement(), event.getPlacement() != null && !event.getPlacement().isEmpty(), "CATALOG");
        }
    }
}
