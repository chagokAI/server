package com.skala.chagok.domain.dashboard.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.dashboard.dto.DashboardResponse;
import com.skala.chagok.domain.inventory.service.InventoryService;
import com.skala.chagok.domain.procurement.dto.ProposalResponse;
import com.skala.chagok.domain.procurement.service.ProcurementQueryService;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.web.*;
import java.math.*;
import java.time.YearMonth;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class DashboardQueryService {
    private final BoxSpecRepository boxes;
    private final InventoryService inventory;
    private final ProcurementQueryService forecasts;
    private final WarehouseQueryGuard warehouses;
    private final ChagokProperties properties;

    public DashboardResponse get(UUID warehouseId, String targetMonth) {
        warehouses.require(warehouseId);
        YearMonth current = YearMonth.now(properties.timezone());
        String month = QueryValues.month(targetMonth, current.plusMonths(1)).toString();
        List<BoxSpec> specs = boxes.findByWarehouseIdAndIsActiveTrue(warehouseId);
        var summary = inventory.getInventories(warehouseId, null, null, 0, 1, null).summary();
        var proposal = forecasts.latestProposal(warehouseId, month);
        Long demand = proposal.result() == null ? null : proposal.result().forecastUsageQty();
        Map<String, long[]> stocks = new TreeMap<>();
        specs.forEach(box -> {
            long[] values = stocks.computeIfAbsent(sizeClass(box.getSizeClass()), key -> new long[4]);
            values[0] += qty(box.getOnHandQty()); values[1] += qty(box.getSafetyQty());
            if (box.getOnHandQty() == null) values[2]++;
            if (box.getSafetyQty() == null) values[3]++;
        });
        var stockBySize = stocks.entrySet().stream().sorted(Map.Entry.comparingByKey(sizeOrder()))
                .map(e -> new DashboardResponse.StockBySize(e.getKey(), e.getValue()[2] > 0 ? null : e.getValue()[0], e.getValue()[3] > 0 ? null : e.getValue()[1])).toList();
        var shortages = specs.stream().filter(box -> box.getOnHandQty() != null && box.getSafetyQty() != null && box.getOnHandQty() < box.getSafetyQty())
                .sorted(Comparator.comparing(BoxSpec::getCode))
                .map(box -> new DashboardResponse.Shortage(box.getId(), box.getCode(), box.getName(), qty(box.getOnHandQty()),
                        box.getSafetyQty(), box.getSafetyQty() - qty(box.getOnHandQty()), box.getDaysToStockout())).toList();
        Map<String, Long> demandBySize = new TreeMap<>();
        if (proposal.result() != null && demand != null) {
            for (ProposalResponse.Item item : proposal.result().items())
                demandBySize.merge(sizeClass(item.boxSpec().sizeClass()), item.forecastUsageQty().longValue(), Long::sum);
        }
        final Long totalDemand = demand;
        var distribution = demandBySize.entrySet().stream().sorted(Map.Entry.comparingByKey(sizeOrder()))
                .map(e -> new DashboardResponse.DemandBySize(e.getKey(), e.getValue(), totalDemand == 0 ? null
                        : BigDecimal.valueOf(e.getValue()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalDemand), 2, RoundingMode.HALF_UP))).toList();
        // 다음 달 말 예상 재고만 계산한다. 다른 대상 월은 중간 월 입출고를 알 수 없어 null이다.
        Long projected = month.equals(current.plusMonths(1).toString()) && demand != null && proposal.result().totalQty() != null
                && specs.stream().allMatch(s -> s.getOnHandQty() != null)
                ? summary.totalOnHandQty() + proposal.result().totalQty() - demand : null;
        return new DashboardResponse(current.toString(), month, summary, stockBySize, shortages, projected,
                new DashboardResponse.DemandForecast(proposal, demand, distribution),
                forecasts.latestCost(warehouseId, current.toString()), forecasts.latestCost(warehouseId, month));
    }

    private static long qty(Integer value) { return value == null ? 0 : value; }
    private static String sizeClass(Character value) { return value == null ? "OTHER" : value.toString(); }
    private static Comparator<String> sizeOrder() {
        return Comparator.comparingInt(value -> switch (value) { case "S" -> 0; case "M" -> 1; case "L" -> 2; default -> 3; });
    }
}
