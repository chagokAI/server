package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.inventory.dto.InventoryItemResponse;
import com.skala.chagok.domain.inventory.dto.InventoryStatus;
import java.math.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 현재 재고는 바꾸지 않고, 같은 사용 계획과 다음 납품일로 화면의 재고 기준을 계산한다. */
public record InventoryPolicyProjection(Integer safetyQty, Integer targetQty, Integer stockLevelPct,
        Integer daysToStockout, InventoryStatus status, int safetyDays, int planningDays, LocalDate nextDeliveryDate) {
    public static final int DEFAULT_SAFETY_DAYS = 7;

    public static InventoryPolicyProjection calculate(BoxSpec box, InventoryUsage usage, LocalDate today,
            LocalDate nextDelivery, int planningLeadDays, int cautionBufferDays) {
        int safetyDays = box.getSafetyDays() == null ? DEFAULT_SAFETY_DAYS : box.getSafetyDays();
        int planningDays = nextDelivery == null ? planningLeadDays
                : Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(today, nextDelivery)));
        Integer monthly = usage.monthly().get(box.getId());
        if (monthly != null && monthly < 0) monthly = null;
        Integer safety = box.getSafetyQty();
        if (safety == null && monthly != null) safety = demand(monthly, usage.month().lengthOfMonth(), safetyDays);
        Integer target = box.getTargetQty();
        if (target == null && safety != null && monthly != null)
            target = Math.addExact(demand(monthly, usage.month().lengthOfMonth(), planningDays), safety);
        Integer days = monthly == null || monthly <= 0 || box.getOnHandQty() == null ? null
                : (int) Math.min(Integer.MAX_VALUE, (long) box.getOnHandQty() * usage.month().lengthOfMonth() / monthly);
        InventoryStatus status = box.getOnHandQty() == null || safety == null ? InventoryStatus.UNKNOWN
                : box.getOnHandQty() < safety ? InventoryStatus.SHORTAGE
                : days != null && days <= planningDays + cautionBufferDays ? InventoryStatus.CAUTION : InventoryStatus.SUFFICIENT;
        return new InventoryPolicyProjection(safety, target, InventoryItemResponse.stockLevelPct(box.getOnHandQty(), target),
                days, status, safetyDays, planningDays, nextDelivery);
    }

    private static int demand(int monthly, int daysInMonth, int days) {
        return BigDecimal.valueOf(monthly).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(daysInMonth), 0, RoundingMode.CEILING).intValueExact();
    }
}
