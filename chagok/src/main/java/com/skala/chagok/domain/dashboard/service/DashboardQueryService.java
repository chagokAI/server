package com.skala.chagok.domain.dashboard.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.dashboard.dto.DashboardResponse;
import com.skala.chagok.domain.inventory.service.InventoryService;
import com.skala.chagok.domain.procurement.dto.ProposalResponse;
import com.skala.chagok.domain.procurement.dto.AgentPlanProjection;
import com.skala.chagok.domain.box.service.BoxSizeClassifier;
import com.skala.chagok.domain.inventory.dto.InventorySummary;
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
    private final BoxSizeClassifier sizes;

    public DashboardResponse get(UUID warehouseId, String targetMonth) {
        warehouses.require(warehouseId);
        YearMonth current = YearMonth.now(properties.timezone());
        String month = QueryValues.month(targetMonth, current.plusMonths(1)).toString();
        List<BoxSpec> specs = boxes.findByWarehouseIdAndIsActiveTrue(warehouseId);
        var summary = inventory.summarize(warehouseId);
        var proposal = forecasts.latestProposal(warehouseId, month, "AUTO");
        var plan = AgentPlanProjection.from(proposal.result(), sizes);
        Long demand = plan.totalUsage();
        var referenceCost = forecasts.latestCost(warehouseId, month);
        Map<String, Integer> monthly = new HashMap<>();
        plan.usage().forEach(row -> monthly.put(row.code(), row.qty()));
        // 과거 제안에 전체 사용 계획이 없으면 저장된 참고 계산을 안전재고 일수 계산의 근거로 쓴다.
        if (referenceCost.result() != null && referenceCost.result().forecast() != null)
            referenceCost.result().forecast().items().forEach(row -> {
                if (row.forecastUsageQty() != null) monthly.putIfAbsent(row.boxSpec().code(), row.forecastUsageQty());
            });
        Map<UUID, Integer> safety = new HashMap<>();
        for (var box : specs) {
            Integer qty = box.getSafetyQty();
            if (qty == null && box.getSafetyDays() != null && monthly.containsKey(box.getCode()))
                qty = BigDecimal.valueOf(monthly.get(box.getCode())).multiply(BigDecimal.valueOf(box.getSafetyDays()))
                        .divide(BigDecimal.valueOf(YearMonth.parse(month).lengthOfMonth()), 0, RoundingMode.CEILING).intValueExact();
            if (qty != null) safety.put(box.getId(), qty);
        }
        Map<String, long[]> stocks = new TreeMap<>();
        specs.forEach(box -> {
            long[] values = stocks.computeIfAbsent(sizeClass(box.getSizeClass()), key -> new long[4]);
            values[0] += qty(box.getOnHandQty()); values[1] += qty(safety.get(box.getId()));
            if (box.getOnHandQty() == null) values[2]++;
            if (!safety.containsKey(box.getId())) values[3]++;
        });
        var stockBySize = stocks.entrySet().stream().sorted(Map.Entry.comparingByKey(sizeOrder()))
                .map(e -> new DashboardResponse.StockBySize(e.getKey(), e.getValue()[2] > 0 ? null : e.getValue()[0], e.getValue()[3] > 0 ? null : e.getValue()[1])).toList();
        var shortages = specs.stream().filter(box -> box.getOnHandQty() != null && safety.containsKey(box.getId()) && box.getOnHandQty() < safety.get(box.getId()))
                .sorted(Comparator.comparing(BoxSpec::getCode))
                .map(box -> new DashboardResponse.Shortage(box.getId(), box.getCode(), box.getName(), qty(box.getOnHandQty()),
                        safety.get(box.getId()), safety.get(box.getId()) - qty(box.getOnHandQty()), box.getDaysToStockout())).toList();
        int unknownSafety = (int) specs.stream().filter(box -> !safety.containsKey(box.getId())).count();
        int unknownInventory = (int) specs.stream().filter(box -> box.getOnHandQty() == null || !safety.containsKey(box.getId())).count();
        summary = new InventorySummary(summary.totalOnHandQty(), summary.activeSpecCount(), shortages.size(),
                shortages.stream().map(DashboardResponse.Shortage::code).toList(), summary.weeklyForecastUsageQty(),
                summary.weeklyForecastChangePct(), summary.avgDaysToStockout(), summary.targetDaysToStockout(),
                summary.lastUpdatedAt(), summary.forecastSource(), unknownInventory);
        Map<String, Long> demandBySize = new TreeMap<>();
        plan.usage().forEach(row -> demandBySize.merge(sizeClass(row.sizeClass()), (long) row.qty(), Long::sum));
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
                forecasts.latestCost(warehouseId, current.toString()), referenceCost,
                new DashboardResponse.PlanMetrics(plan.basis(), plan.boxCostKrw(), plan.monthlySavingsKrw(),
                        proposal.result() == null ? null : proposal.result().totalAmountKrw(), plan.savingsBasis(), unknownSafety));
    }

    private static long qty(Integer value) { return value == null ? 0 : value; }
    private static String sizeClass(Character value) { return value == null ? "OTHER" : value.toString(); }
    private static Comparator<String> sizeOrder() {
        return Comparator.comparingInt(value -> switch (value) { case "S" -> 0; case "M" -> 1; case "L" -> 2; default -> 3; });
    }
}
