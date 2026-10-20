package com.skala.chagok.domain.purchase.repository;

import com.skala.chagok.domain.purchase.entity.PurchaseOrder;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID>, JpaSpecificationExecutor<PurchaseOrder> {

    boolean existsByProposalId(UUID proposalId);

    long countByPoNoStartingWith(String poNoPrefix);

    @Override @EntityGraph(attributePaths = {"supplier", "requestedBy", "approver"})
    Page<PurchaseOrder> findAll(Specification<PurchaseOrder> specification, Pageable pageable);

    @EntityGraph(attributePaths = {"supplier", "requestedBy", "approver", "warehouse", "proposal"})
    Optional<PurchaseOrder> findByIdAndWarehouseId(UUID id, UUID warehouseId);
}
