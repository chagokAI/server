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
        List<Item> items = rows.stream().map(Item::from).sorted(Comparator.comparing(Item::boxSpec, BoxSnapshot.ORDER)).toList();
        boolean hasAmounts = !items.isEmpty() && items.stream().allMatch(i -> i.amountKrw() != null);
        Long forecastUsage = items.isEmpty() || items.stream().anyMatch(i -> i.forecastUsageQty() == null) ? null
                : items.stream().mapToLong(Item::forecastUsageQty).sum();
        return new ProposalResponse(proposal.getId(), proposal.getJobId(), proposal.getTargetMonth(),
                proposal.getDataAsOf(), proposal.getGeneratedAt(),
                items.stream().anyMatch(i -> i.proposedQty() == null) ? null : items.stream().mapToLong(Item::proposedQty).sum(),
                hasAmounts ? items.stream().mapToLong(Item::amountKrw).sum() : null, forecastUsage, "KRW", false,
                !items.isEmpty() && items.stream().allMatch(i -> i.snapshotAvailable() && i.unitPriceKrw() != null),
                proposal.getSummary() == null ? Map.of() : proposal.getSummary(), items);
    }
}
