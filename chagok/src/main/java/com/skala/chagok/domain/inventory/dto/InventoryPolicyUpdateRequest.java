package com.skala.chagok.domain.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record InventoryPolicyUpdateRequest(
        @NotNull @jakarta.validation.constraints.Min(0) Integer expectedVersion,
        @PositiveOrZero Integer safetyQty,
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(366) Integer safetyDays,
        Boolean alertEnabled,
        @PositiveOrZero Integer targetQty,
        Boolean automaticTarget
) {
    public InventoryPolicyUpdateRequest(Integer expectedVersion, Integer safetyQty, Integer safetyDays,
            Boolean alertEnabled, Integer targetQty) {
        this(expectedVersion, safetyQty, safetyDays, alertEnabled, targetQty, null);
    }
}
