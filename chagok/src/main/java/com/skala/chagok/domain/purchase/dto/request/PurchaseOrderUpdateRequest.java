package com.skala.chagok.domain.purchase.dto.request;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public record PurchaseOrderUpdateRequest(@NotNull @Min(0) Integer expectedVersion, LocalDate requestedDeliveryDate,
        PurchaseOrderStatus status, @Size(max = 500) String decisionReason, @Valid List<Item> items) {
    public record Item(@NotNull UUID boxSpecId, @NotNull @PositiveOrZero Integer qty) {}
}
