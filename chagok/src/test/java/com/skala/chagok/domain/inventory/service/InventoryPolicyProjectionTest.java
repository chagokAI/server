package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.inventory.dto.InventoryStatus;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InventoryPolicyProjectionTest {
    final UUID id = UUID.randomUUID();
    final LocalDate today = LocalDate.of(2026, 10, 26);
    final InventoryUsage usage = new InventoryUsage(YearMonth.of(2026, 10), Map.of(id, 3100), true, null);
    BoxSpec box(Integer safety, Integer target) {
        return BoxSpec.builder().id(id).onHandQty(900).safetyQty(safety).targetQty(target)
                .supplier(Supplier.builder().leadTimeDays((short) 1).build()).build();
    }
    InventoryPolicyProjection calculate(BoxSpec box, LocalDate date) {
        return InventoryPolicyProjection.calculate(box, usage, today, date, 5, 3);
    }
    @Test void defaultsToSevenSafetyDaysAndFiveDeliveryDaysWithoutChangingSupplier() {
        var box = box(null, null);
        var p = calculate(box, null);
        assertThat(p.safetyQty()).isEqualTo(700);
        assertThat(p.targetQty()).isEqualTo(1200);
        assertThat(p.stockLevelPct()).isEqualTo(75);
        assertThat(p.daysToStockout()).isEqualTo(9);
        assertThat(box.getSafetyQty()).isNull();
        assertThat(box.getTargetQty()).isNull();
        assertThat(box.getSupplier().getLeadTimeDays()).isEqualTo((short) 1);
    }
    @Test void confirmedDeliveryDateOverridesAssumedLeadTimeAndChangesWithTheDate() {
        var box = box(null, null);
        assertThat(calculate(box, today.plusDays(3)).targetQty()).isEqualTo(1000);
        assertThat(calculate(box, today.plusDays(10)).targetQty()).isEqualTo(1700);
        var tomorrow = InventoryPolicyProjection.calculate(box, usage, today.plusDays(1), today.plusDays(10), 5, 3);
        assertThat(tomorrow.targetQty()).isEqualTo(1600);
    }
    @Test void explicitQuantitiesIncludingZeroTakePriority() {
        var p = calculate(box(0, 3000), today.plusDays(10));
        assertThat(p.safetyQty()).isZero();
        assertThat(p.targetQty()).isEqualTo(3000);
        assertThat(p.stockLevelPct()).isEqualTo(30);
    }
    @Test void actualConsumptionChangesRatioAndShortageWithoutChangingTarget() {
        var box = box(null, null);
        box.changeStock(600, Instant.now());
        var p = calculate(box, null);
        assertThat(p.targetQty()).isEqualTo(1200);
        assertThat(p.stockLevelPct()).isEqualTo(50);
        assertThat(p.status()).isEqualTo(InventoryStatus.SHORTAGE);
    }
    @Test void missingUsageRemainsUnknownRatherThanInventingZero() {
        var p = InventoryPolicyProjection.calculate(box(null, null),
                new InventoryUsage(usage.month(), Map.of(), false, null), today, null, 5, 3);
        assertThat(p.safetyQty()).isNull();
        assertThat(p.targetQty()).isNull();
        assertThat(p.stockLevelPct()).isNull();
        assertThat(p.status()).isEqualTo(InventoryStatus.UNKNOWN);
    }
}
