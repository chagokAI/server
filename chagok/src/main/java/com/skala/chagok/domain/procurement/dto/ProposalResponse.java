package com.skala.chagok.domain.procurement.dto;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.procurement.entity.*;
import java.time.Instant;
import java.util.*;

public record ProposalResponse(UUID id, UUID jobId, String targetMonth, Instant dataAsOf, Instant generatedAt,
                               Long totalQty, Long totalAmountKrw, Long forecastUsageQty, String currency,
                               boolean vatIncluded, boolean snapshotComplete, Map<String, Object> analysis,
                               List<Item> items) {
    public record Item(BoxSnapshot boxSpec, Integer onHandQty, Integer safetyQty, Integer forecastUsageQty,
                       Integer proposedQty, Integer unitPriceKrw, Long amountKrw, boolean snapshotAvailable) {
        static Item from(ProcurementProposalItem item) {
            Long amount = item.getProposedQty() == null || item.getUnitPriceKrw() == null ? null
                    : Math.multiplyExact(item.getProposedQty().longValue(), item.getUnitPriceKrw().longValue());
            return new Item(item.getBoxSnapshot() == null ? BoxSnapshot.from(item.getBoxSpec()) : item.getBoxSnapshot(),
                    item.getOnHandQty(), item.getSafetyQty(), item.getForecastUsageQty(), item.getProposedQty(),
                    item.getUnitPriceKrw(), amount, item.getBoxSnapshot() != null);
        }
    }

    public static ProposalResponse from(ProcurementProposal proposal, List<ProcurementProposalItem> rows) {
        Map<String, Object> analysis = proposal.getSummary() == null ? Map.of() : proposal.getSummary();
        var projected = new ArrayList<>(rows.stream().map(Item::from).toList());
        Set<String> codes = new HashSet<>();
        projected.forEach(item -> codes.add(item.boxSpec().code()));
        // 승인된 신규 규격도 수량에 포함한다. 실제 규격·발주 품목 등록은 초안 반영 시 원자적으로 처리한다.
        for (var line : AgentProposalData.pendingLines(analysis, codes)) {
            String code = (String) line.get("boxCode");
            if (!codes.add(code)) continue;
            int[] dims = AgentProposalData.dimensions(line.get("dims"));
            if (dims == null) continue;
            projected.add(new Item(new BoxSnapshot(null, code, (String) line.get("boxName"), null, dims[0], dims[1], dims[2]),
                    AgentProposalData.integer(line.get("currentQty")), AgentProposalData.integer(line.get("safetyQty")),
                    AgentProposalData.integer(line.get("expectedUsage")), AgentProposalData.integer(line.get("qty")),
                    AgentProposalData.integer(line.get("unitPriceKrw")), amount(line), false));
        }
        List<Item> items = projected.stream().sorted(Comparator.comparing(Item::boxSpec, BoxSnapshot.ORDER)).toList();
        boolean hasAmounts = !items.isEmpty() && items.stream().allMatch(i -> i.amountKrw() != null);
        Long forecastUsage = items.isEmpty() || items.stream().anyMatch(i -> i.forecastUsageQty() == null) ? null
                : items.stream().mapToLong(Item::forecastUsageQty).sum();
        return new ProposalResponse(proposal.getId(), proposal.getJobId(), proposal.getTargetMonth(),
                proposal.getDataAsOf(), proposal.getGeneratedAt(),
                items.stream().anyMatch(i -> i.proposedQty() == null) ? null : items.stream().mapToLong(Item::proposedQty).sum(),
                hasAmounts ? items.stream().mapToLong(Item::amountKrw).sum() : null, forecastUsage, "KRW", false,
                !items.isEmpty() && items.stream().allMatch(i -> i.snapshotAvailable() && i.unitPriceKrw() != null),
                analysis, items);
    }

    private static Long amount(Map<String, Object> line) {
        Integer qty = AgentProposalData.integer(line.get("qty")), price = AgentProposalData.integer(line.get("unitPriceKrw"));
        return qty == null || price == null ? null : Math.multiplyExact(qty.longValue(), price.longValue());
    }
}
