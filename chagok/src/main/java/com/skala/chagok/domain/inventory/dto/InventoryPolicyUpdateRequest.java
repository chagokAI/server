package com.skala.chagok.domain.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record InventoryPolicyUpdateRequest(
        @NotNull Integer expectedVersion,
        @NotNull @PositiveOrZero Integer safetyQty
) {
}
