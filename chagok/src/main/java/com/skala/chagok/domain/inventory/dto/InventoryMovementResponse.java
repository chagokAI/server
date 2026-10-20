package com.skala.chagok.domain.inventory.dto;
import com.skala.chagok.domain.inventory.entity.InventoryMovement;
import java.time.*;
import java.util.UUID;

public record InventoryMovementResponse(UUID id, UUID requestId, UUID boxSpecId, UUID purchaseOrderId,
        String type, Long deltaQty, Integer beforeQty, int afterQty, LocalDate occurredOn, String memo, Instant createdAt) {
    public static InventoryMovementResponse from(InventoryMovement m) {
        return new InventoryMovementResponse(m.getId(), m.getRequestId(), m.getBoxSpecId(), m.getPurchaseOrderId(),
                m.getType(), m.getDeltaQty(), m.getBeforeQty(), m.getAfterQty(), m.getOccurredOn(), m.getMemo(), m.getCreatedAt());
    }
}
