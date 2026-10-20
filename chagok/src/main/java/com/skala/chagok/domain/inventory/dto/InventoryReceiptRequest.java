package com.skala.chagok.domain.inventory.dto;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public record InventoryReceiptRequest(@NotNull UUID requestId, @NotNull UUID boxSpecId,
        @NotNull @Min(0) Integer expectedVersion, UUID purchaseOrderId,
        @NotNull @Positive Integer qty, @NotNull LocalDate receivedDate, @Size(max = 500) String memo) {}
