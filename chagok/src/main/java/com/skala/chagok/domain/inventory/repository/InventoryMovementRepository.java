package com.skala.chagok.domain.inventory.repository;

import com.skala.chagok.domain.inventory.entity.InventoryMovement;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, UUID> {
    boolean existsByBoxSpecId(UUID boxSpecId);
    Optional<InventoryMovement> findByWarehouseIdAndRequestId(UUID warehouseId, UUID requestId);
    List<InventoryMovement> findByWarehouseIdAndBoxSpecIdOrderByCreatedAtDesc(UUID warehouseId, UUID boxSpecId);
    org.springframework.data.domain.Page<InventoryMovement> findByWarehouseIdAndBoxSpecId(UUID warehouseId, UUID boxSpecId,
            org.springframework.data.domain.Pageable pageable);
    @Query("select coalesce(sum(m.deltaQty), 0) from InventoryMovement m where m.purchaseOrderId = :orderId "
            + "and m.boxSpecId = :boxSpecId and m.type = 'RECEIPT'")
    long receivedQty(UUID orderId, UUID boxSpecId);
}
