package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalItemRepository;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalRepository;
import com.skala.chagok.domain.procurement.dto.AgentProposalData;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.global.config.ChagokProperties;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * 참고 수량을 읽는 곳을 한곳으로 모은다. 안전 재고 정책과 박스 재고/규격 목록이 같은 제안 버전을 봐야 한다.
 */
@Component
@RequiredArgsConstructor
public class InventoryUsageReader {

    private final ProcurementProposalRepository proposals;
    private final ProcurementProposalItemRepository proposalItems;
    private final ChagokProperties properties;
    private final BoxSpecRepository boxes;

    public InventoryUsage read(UUID warehouseId) {
        YearMonth month = YearMonth.now(properties.timezone());
        var candidates = proposals.findByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, month.toString());
        var selected = candidates.stream().filter(p -> p.getSummary() != null && p.getSummary().containsKey("clientView")
                && AgentProposalData.approved(p.getSummary())).findFirst().or(() -> candidates.stream().findFirst());
        return selected.map(proposal -> {
                    var lines = proposalItems.findByProposalId(proposal.getId());
                    Map<UUID, Integer> monthly = new HashMap<>();
                    lines.stream().filter(item -> item.getForecastUsageQty() != null)
                            .forEach(item -> monthly.put(item.getBoxSpec().getId(), item.getForecastUsageQty()));
                    var plan = AgentProposalData.rows(proposal.getSummary() == null ? null : proposal.getSummary().get("usagePlan"));
                    var byCode = new HashMap<String, UUID>();
                    if (!plan.isEmpty()) boxes.findByWarehouseIdAndIsActiveTrue(warehouseId)
                            .forEach(box -> byCode.put(box.getCode(), box.getId()));
                    boolean complete = !lines.isEmpty() && monthly.size() == lines.size();
                    if (!plan.isEmpty()) {
                        complete = true;
                        for (var row : plan) {
                            UUID id = byCode.get(row.get("code"));
                            Integer qty = AgentProposalData.integer(row.get("expectedUsage"));
                            if (id == null || qty == null || qty < 0) complete = false;
                            else monthly.put(id, qty);
                        }
                    }
                    return new InventoryUsage(month, monthly, complete,
                            new InventorySummary.ForecastSource(proposal.getId(), proposal.getTargetMonth(),
                                    proposal.getDataAsOf(), proposal.getGeneratedAt()));
                })
                .orElse(new InventoryUsage(month, Map.of(), false, null));
    }
}
