package com.skala.chagok.domain.procurement.dto;

import com.skala.chagok.domain.box.service.BoxSizeClassifier;
import java.util.*;

/** 주문이 필요한 품목과 전체 예상 소모 계획을 구분한다. */
public record AgentPlanProjection(List<Usage> usage, Long totalUsage, Long boxCostKrw,
                                  Long monthlySavingsKrw, String basis, String savingsBasis) {
    public record Usage(String code, Character sizeClass, int qty) {}

    public static AgentPlanProjection from(ProposalResponse proposal, BoxSizeClassifier sizes) {
        if (proposal == null) return new AgentPlanProjection(List.of(), null, null, null, "계산 결과 없음", null);
        var analysis = proposal.analysis();
        boolean agent = analysis.get("clientView") instanceof Map<?, ?>;
        if (!agent) {
            var usage = proposal.items().stream().filter(i -> i.forecastUsageQty() != null)
                    .map(i -> new Usage(i.boxSpec().code(), i.boxSpec().sizeClass(), i.forecastUsageQty())).toList();
            Long cost = proposal.items().isEmpty() || proposal.items().stream().anyMatch(i -> i.forecastUsageQty() == null || i.unitPriceKrw() == null)
                    ? null : proposal.items().stream().mapToLong(i -> Math.multiplyExact(i.forecastUsageQty().longValue(), i.unitPriceKrw())).sum();
            return new AgentPlanProjection(usage, proposal.forecastUsageQty(), cost, null, "관측 기반 참고 계산", null);
        }
        var plan = AgentProposalData.rows(analysis.get("usagePlan"));
        List<Usage> usage = new ArrayList<>();
        long cost = 0;
        boolean complete = !plan.isEmpty();
        for (var row : plan) {
            Integer qty = AgentProposalData.integer(row.get("expectedUsage")), price = AgentProposalData.integer(row.get("unitPrice"));
            int[] dims = AgentProposalData.dimensions(row.get("dims"));
            if (!(row.get("code") instanceof String code) || qty == null || qty < 0 || price == null || price < 0 || dims == null) {
                complete = false;
                break;
            }
            usage.add(new Usage(code, sizes.classify(dims[0], dims[1], dims[2]), qty));
            cost = Math.addExact(cost, Math.multiplyExact(qty.longValue(), price.longValue()));
        }
        Long forecast = AgentProposalData.number(AgentProposalData.object(analysis.get("forecast")).get("forecast"));
        Long savings = AgentProposalData.number(AgentProposalData.object(analysis.get("simulation")).get("monthly_saving"));
        var newSpecs = AgentProposalData.rows(analysis.get("newSpecs"));
        if (savings == null && newSpecs.size() == 1) savings = AgentProposalData.number(newSpecs.getFirst().get("monthlySaving"));
        if (savings == null && analysis.containsKey("newSpecs") && newSpecs.isEmpty()) savings = 0L;
        return new AgentPlanProjection(complete ? List.copyOf(usage) : List.of(),
                complete ? usage.stream().mapToLong(Usage::qty).sum() : forecast,
                complete ? cost : null, savings, "AI 출고 예측 · " + Objects.toString(AgentProposalData.object(analysis.get("forecast")).get("method"), "저장된 제안서"),
                savings == null ? null : "최근 90일 출고의 월평균 · 박스비·운임 도입 시뮬레이션");
    }
}
