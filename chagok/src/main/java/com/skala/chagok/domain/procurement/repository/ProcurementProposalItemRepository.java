package com.skala.chagok.domain.procurement.repository;

import com.skala.chagok.domain.procurement.entity.ProcurementProposalItem;
import com.skala.chagok.domain.procurement.entity.ProcurementProposalItemId;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementProposalItemRepository
        extends JpaRepository<ProcurementProposalItem, ProcurementProposalItemId> {

    boolean existsByBoxSpecId(UUID boxSpecId);
}
