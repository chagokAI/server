package com.skala.chagok.domain.inventory.dto;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record InventoryAdjustmentRequest(@NotNull UUID requestId, @NotNull UUID boxSpecId,
        @NotNull @Min(0) Integer expectedVersion, @NotNull @PositiveOrZero Integer onHandQty,
        @NotBlank @Size(max = 500) String reason) {}
