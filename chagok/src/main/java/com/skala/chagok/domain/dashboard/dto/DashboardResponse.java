package com.skala.chagok.domain.dashboard.dto;

import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.domain.procurement.dto.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DashboardResponse(String currentMonth, String targetMonth, InventorySummary inventory,
                                 List<StockBySize> stockBySize, List<Shortage> shortages, Long projectedStockQty,
                                 DemandForecast demandForecast, LatestResult<CostForecastResponse> currentMonthCost,
                                 LatestResult<CostForecastResponse> targetMonthCost, PlanMetrics planMetrics,
                                 ProjectedStock projectedStock) {
    /**
     * 다음 달 말 예상 재고의 근거. endQty = startQty − consumeBeforeQty + inboundQty + orderQty − demandQty.
     * consumeBeforeQty = 다음 달 1일까지 남은 일수(remainingDays) × 다음 달 일평균 소모,
     * inboundQty = 승인·배송 중이지만 아직 입고되지 않은 다른 발주, orderQty = 다음 달 제안 주문 중 아직 입고되지 않은 수량
     */
    public record ProjectedStock(long startQty, long consumeBeforeQty, long inboundQty, long orderQty, long demandQty,
                                 Long safetyQty, long endQty, BigDecimal remainingDays, List<SizeProjection> bySize) {}
    /** 크기별(S·M·L) 다음 달 말 예상 재고. 합계와 같은 식이다 */
    public record SizeProjection(String sizeClass, long startQty, long consumeBeforeQty, long inboundQty, long orderQty,
                                 long demandQty, long endQty) {}
    public record PlanMetrics(String basis, Long estimatedBoxCostKrw, Long monthlySavingsKrw,
                              Long plannedPurchaseCostKrw, String savingsBasis, int unknownSafetySpecCount) {}
    public record StockBySize(String sizeClass, Long onHandQty, Long safetyQty) {}
    public record Shortage(UUID boxSpecId, String code, String name, long onHandQty, long safetyQty,
                           long shortageQty, Integer daysToStockout) {}
    public record DemandForecast(LatestResult<ProposalResponse> proposal, Long totalForecastUsageQty,
                                  List<DemandBySize> bySize) {}
    public record DemandBySize(String sizeClass, long forecastUsageQty, BigDecimal sharePct) {}
}
