package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxMaterial;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.inventory.dto.InventoryItemResponse;
import com.skala.chagok.domain.inventory.dto.InventoryStatus;
import com.skala.chagok.domain.inventory.service.InventoryPolicyProjection;
import com.skala.chagok.domain.supplier.entity.Supplier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * 박스 재고/규격 관리 표의 한 행. 재고는 box_spec에 함께 저장되므로 규격 한 건에 재고 한 건이 늘 따라온다.
 * 재고 정책 수정(INV-API-02)의 경로 변수와 version은 모두 이 규격의 id·version을 그대로 쓴다.
 */
public record BoxStockResponse(
        UUID id,
        String code,
        String name,
        Character sizeClass,
        Integer innerWidthMm,
        Integer innerDepthMm,
        Integer innerHeightMm,
        String material,
        String materialLabel,
        SupplierSummary supplier,
        Integer unitPriceKrw,
        Boolean isCustom,
        Boolean isActive,
        String description,
        Integer version,
        long linkedProductCount,
        Stock stock
) {

    /** 안전 재고 수정 모달의 "공급사 리드타임 7일 · 그린팩코리아" 표시용 */
    public record SupplierSummary(UUID id, String name, Short leadTimeDays) {

        static SupplierSummary from(Supplier supplier) {
            return supplier == null ? null
                    : new SupplierSummary(supplier.getId(), supplier.getName(), supplier.getLeadTimeDays());
        }
    }

    /**
     * @param dailyUsage 최신 제안의 참고 일평균이며 실제 박스 소모량이 아니다
     * @param usageSource REFERENCE_FORECAST 또는 NOT_AVAILABLE
     */
    public record Stock(
            Integer onHandQty,
            Integer safetyQty,
            Integer targetQty,
            Integer stockLevelPct,
            Integer daysToStockout,
            InventoryStatus status,
            Integer safetyDays,
            Boolean alertEnabled,
            BigDecimal dailyUsage,
            String usageSource,
            Instant usageDataAsOf,
            OffsetDateTime stockUpdatedAt,
            OffsetDateTime forecastUpdatedAt,
            Integer configuredSafetyQty,
            Integer configuredTargetQty,
            Integer planningDays,
            java.time.LocalDate nextDeliveryDate
    ) {
    }

    public static BoxStockResponse from(BoxSpec boxSpec, long linkedProductCount, InventoryStatus status,
                                        ZoneId zone, BigDecimal dailyUsage, Instant usageDataAsOf) {
        return from(boxSpec, linkedProductCount, status, zone, dailyUsage, usageDataAsOf, null);
    }

    public static BoxStockResponse from(BoxSpec boxSpec, long linkedProductCount, InventoryStatus status,
            ZoneId zone, BigDecimal dailyUsage, Instant usageDataAsOf, InventoryPolicyProjection policy) {
        // 크롤링 원본("골판지 B골")처럼 코드가 아닌 값도 코드·표시 이름으로 바꿔 내려준다. 해석할 수 없으면 원본 그대로.
        Optional<BoxMaterial> material = BoxMaterial.parse(boxSpec.getMaterial());
        return new BoxStockResponse(
                boxSpec.getId(),
                boxSpec.getCode(),
                boxSpec.getName(),
                boxSpec.getSizeClass(),
                boxSpec.getInnerWidthMm(),
                boxSpec.getInnerDepthMm(),
                boxSpec.getInnerHeightMm(),
                material.map(BoxMaterial::name).orElse(boxSpec.getMaterial()),
                material.map(BoxMaterial::getLabel).orElse(boxSpec.getMaterial()),
                SupplierSummary.from(boxSpec.getSupplier()),
                boxSpec.getUnitPriceKrw(),
                boxSpec.getIsCustom(),
                boxSpec.getIsActive(),
                boxSpec.getDescription(),
                boxSpec.getVersion(),
                linkedProductCount,
                new Stock(
                        boxSpec.getOnHandQty(),
                        policy == null ? boxSpec.getSafetyQty() : policy.safetyQty(),
                        policy == null ? boxSpec.getTargetQty() : policy.targetQty(),
                        policy == null ? InventoryItemResponse.stockLevelPct(boxSpec.getOnHandQty(), boxSpec.getTargetQty()) : policy.stockLevelPct(),
                        policy == null ? boxSpec.getDaysToStockout() : policy.daysToStockout(),
                        policy == null ? status : policy.status(),
                        policy == null ? boxSpec.getSafetyDays() : policy.safetyDays(),
                        boxSpec.getAlertEnabled(),
                        dailyUsage,
                        dailyUsage == null ? "NOT_AVAILABLE" : "REFERENCE_FORECAST",
                        usageDataAsOf,
                        boxSpec.getStockUpdatedAt() == null ? null
                                : boxSpec.getStockUpdatedAt().atZone(zone).toOffsetDateTime(),
                        boxSpec.getForecastUpdatedAt() == null ? null
                                : boxSpec.getForecastUpdatedAt().atZone(zone).toOffsetDateTime(),
                        boxSpec.getSafetyQty(), boxSpec.getTargetQty(),
                        policy == null ? null : policy.planningDays(), policy == null ? null : policy.nextDeliveryDate()
                )
        );
    }
}
