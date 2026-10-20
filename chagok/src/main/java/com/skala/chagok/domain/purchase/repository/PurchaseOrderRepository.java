package com.skala.chagok.domain.purchase.repository;

import com.skala.chagok.domain.purchase.entity.PurchaseOrder;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    boolean existsByProposalId(UUID proposalId);

    long countByPoNoStartingWith(String poNoPrefix);
}
