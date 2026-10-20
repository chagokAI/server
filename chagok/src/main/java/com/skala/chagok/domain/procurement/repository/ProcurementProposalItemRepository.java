package com.skala.chagok.domain.procurement.repository;

import com.skala.chagok.domain.procurement.entity.ProcurementProposalItem;
import com.skala.chagok.domain.procurement.entity.ProcurementProposalItemId;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcurementProposalItemRepository
        extends JpaRepository<ProcurementProposalItem, ProcurementProposalItemId> {

    boolean existsByBoxSpecId(UUID boxSpecId);

    /** 제안서의 규격별 다음 달 예상 소모량 합계. 항목이 없으면 null. */
    @Query("select sum(i.forecastUsageQty) from ProcurementProposalItem i where i.proposal.id = :proposalId")
    Long sumForecastUsageQty(@Param("proposalId") UUID proposalId);
}
