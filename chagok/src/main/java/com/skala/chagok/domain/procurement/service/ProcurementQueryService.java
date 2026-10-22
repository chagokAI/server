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
    public record ProposalChoice(UUID id, String targetMonth, java.time.Instant generatedAt, boolean hasNewSpecs) {}
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

    public java.util.List<ProposalChoice> listAgentProposals(UUID warehouseId) {
        warehouses.require(warehouseId);
        return proposals.findByWarehouseIdOrderByGeneratedAtDescIdDesc(warehouseId, org.springframework.data.domain.PageRequest.of(0, 100))
                .stream().filter(p -> p.getSummary() != null && p.getSummary().containsKey("clientView") && AgentProposalData.approved(p.getSummary()))
                .map(p -> new ProposalChoice(p.getId(), p.getTargetMonth().trim(), p.getGeneratedAt(),
                        !AgentProposalData.rows(p.getSummary().get("newSpecs")).isEmpty())).toList();
    }

    /** AI 개선 제안과 참고 계산을 따로 선택한다. AUTO는 승인된 AI 개선 제안을 우선한다. */
    public LatestResult<ProposalResponse> latestProposal(UUID warehouseId, String targetMonth, String source) {
        if ("LATEST".equals(source)) return latestProposal(warehouseId, targetMonth);
        if (!java.util.Set.of("AUTO", "AI", "REFERENCE").contains(source))
            throw BusinessException.invalidField("source", "AUTO, AI, REFERENCE, LATEST 중 하나여야 합니다.");
        warehouses.require(warehouseId);
        String month = month(targetMonth);
        var candidates = proposals.findByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, month);
        var selected = candidates.stream().filter(p -> {
            var summary = p.getSummary();
            return summary != null && ("REFERENCE".equals(source)
                    ? "OBSERVATION_BASELINE".equals(summary.get("method"))
                    : summary.containsKey("clientView") && AgentProposalData.approved(summary));
        }).findFirst();
        if (selected.isEmpty() && "AUTO".equals(source)) selected = candidates.stream().findFirst();
        ProposalResponse result = selected.map(p -> ProposalResponse.from(p, items.findByProposalId(p.getId()))).orElse(null);
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
