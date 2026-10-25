package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalItemRepository;
import com.skala.chagok.domain.procurement.repository.ProcurementProposalRepository;
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

    public InventoryUsage read(UUID warehouseId) {
        YearMonth month = YearMonth.now(properties.timezone());
        return proposals
                .findFirstByWarehouseIdAndTargetMonthOrderByGeneratedAtDescIdDesc(warehouseId, month.toString())
                .map(proposal -> {
                    var lines = proposalItems.findByProposalId(proposal.getId());
                    Map<UUID, Integer> monthly = new HashMap<>();
                    lines.stream().filter(item -> item.getForecastUsageQty() != null)
                            .forEach(item -> monthly.put(item.getBoxSpec().getId(), item.getForecastUsageQty()));
                    return new InventoryUsage(month, monthly, !lines.isEmpty() && monthly.size() == lines.size(),
                            new InventorySummary.ForecastSource(proposal.getId(), proposal.getTargetMonth(),
                                    proposal.getDataAsOf(), proposal.getGeneratedAt()));
                })
                .orElse(new InventoryUsage(month, Map.of(), false, null));
    }
}
