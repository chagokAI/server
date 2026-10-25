package com.skala.chagok.domain.inventory.dto;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.supplier.entity.Supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 재고 관리 표의 한 행. 재고는 box_spec에 함께 저장되므로 inventoryId는 박스 규격 id와 같다.
 */
public record InventoryItemResponse(
        UUID inventoryId,
        String boxCode,
        String boxName,
        Integer innerWidthMm,
        Integer innerDepthMm,
        Integer innerHeightMm,
        Integer onHandQty,
        Integer safetyQty,
        Integer targetQty,
        Integer stockLevelPct,
        Integer daysToStockout,
        InventoryStatus status,
        SupplierSummary supplier,
        OffsetDateTime stockUpdatedAt,
        Integer version,
        OffsetDateTime forecastUpdatedAt,
        Integer safetyDays,
        Boolean alertEnabled,
        BigDecimal dailyUsage,
        String usageSource,
        java.time.Instant usageDataAsOf
) {

    /** 안전 재고 수정 모달의 "공급사 리드타임 7일 · 그린팩코리아" 표시용 */
    public record SupplierSummary(UUID id, String name, Short leadTimeDays) {

        static SupplierSummary from(Supplier supplier) {
            return supplier == null ? null
                    : new SupplierSummary(supplier.getId(), supplier.getName(), supplier.getLeadTimeDays());
        }
    }

    public static InventoryItemResponse from(BoxSpec boxSpec, InventoryStatus status, ZoneId zone) {
        return from(boxSpec, status, zone, null, null);
    }

    public static InventoryItemResponse from(BoxSpec boxSpec, InventoryStatus status, ZoneId zone, BigDecimal dailyForecast, java.time.Instant usageDataAsOf) {
        return new InventoryItemResponse(
                boxSpec.getId(),
                boxSpec.getCode(),
                boxSpec.getName(),
                boxSpec.getInnerWidthMm(),
                boxSpec.getInnerDepthMm(),
                boxSpec.getInnerHeightMm(),
                boxSpec.getOnHandQty(),
                boxSpec.getSafetyQty(),
                boxSpec.getTargetQty(),
                stockLevelPct(boxSpec.getOnHandQty(), boxSpec.getTargetQty()),
                boxSpec.getDaysToStockout(),
                status,
                SupplierSummary.from(boxSpec.getSupplier()),
                boxSpec.getStockUpdatedAt() == null ? null : boxSpec.getStockUpdatedAt().atZone(zone).toOffsetDateTime(),
                boxSpec.getVersion(),
                boxSpec.getForecastUpdatedAt() == null ? null : boxSpec.getForecastUpdatedAt().atZone(zone).toOffsetDateTime(),
                boxSpec.getSafetyDays(),
                boxSpec.getAlertEnabled(),
                dailyForecast,
                dailyForecast == null ? "NOT_AVAILABLE" : "REFERENCE_FORECAST",
                usageDataAsOf
        );
    }

    /** 재고 수준 = 현재 재고 ÷ 목표 재고 × 100, 정수 반올림, 최대 100. 목표 재고가 없으면 null. */
    public static Integer stockLevelPct(Integer onHandQty, Integer targetQty) {
        if (onHandQty == null || targetQty == null || targetQty <= 0) {
            return null;
        }
        int onHand = onHandQty == null ? 0 : onHandQty;
        int pct = BigDecimal.valueOf(onHand).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(targetQty), 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.min(pct, 100);
    }
}
