package com.skala.chagok.domain.purchase.repository;

import com.skala.chagok.domain.purchase.entity.PurchaseOrderItem;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderItemId;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderItemRepository extends JpaRepository<PurchaseOrderItem, PurchaseOrderItemId> {

    boolean existsByBoxSpecId(UUID boxSpecId);
}
