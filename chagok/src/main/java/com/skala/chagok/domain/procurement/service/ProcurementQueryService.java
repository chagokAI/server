package com.skala.chagok.domain.procurement.service;

import com.skala.chagok.domain.job.service.JobQueryService;
import com.skala.chagok.domain.procurement.dto.*;
import com.skala.chagok.domain.procurement.repository.*;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.*;
import java.time.YearMonth;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ProcurementQueryService {
    private final ProcurementProposalRepository proposals;
    private final ProcurementProposalItemRepository items;
    private final CostForecastRepository forecasts;
    private final JobQueryService jobs;
    private final WarehouseQueryGuard warehouses;
    private final ChagokProperties properties;

    public LatestResult<ProposalResponse> latestProposal(UUID warehouseId, String targetMonth) {
        warehouses.require(warehouseId);
        String month = month(targetMonth);
        ProposalResponse result = proposals.findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, month)
                .map(p -> ProposalResponse.from(p, items.findByProposalId(p.getId()))).orElse(null);
        return LatestResult.of(month, result, jobs.latest(warehouseId, month));
    }

    public ProposalResponse proposal(UUID warehouseId, UUID proposalId) {
        warehouses.require(warehouseId);
        var proposal = proposals.findByIdAndWarehouseId(proposalId, warehouseId)
                .orElseThrow(() -> BusinessException.notFound("발주 제안서"));
        return ProposalResponse.from(proposal, items.findByProposalId(proposalId));
    }

    public LatestResult<CostForecastResponse> latestCost(UUID warehouseId, String targetMonth) {
        warehouses.require(warehouseId);
        String month = month(targetMonth);
        return LatestResult.of(month, forecasts.findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, month)
                .map(CostForecastResponse::from).orElse(null), jobs.latest(warehouseId, month));
    }

    private String month(String value) {
        return QueryValues.month(value, YearMonth.now(properties.timezone()).plusMonths(1)).toString();
    }
}
