package com.skala.chagok.domain.procurement.repository;

import com.skala.chagok.domain.procurement.entity.ProcurementProposal;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcurementProposalRepository extends JpaRepository<ProcurementProposal, UUID> {
}
