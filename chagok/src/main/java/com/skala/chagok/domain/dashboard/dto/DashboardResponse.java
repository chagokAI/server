package com.skala.chagok.domain.dashboard.dto;

import com.skala.chagok.domain.inventory.dto.InventorySummary;
import com.skala.chagok.domain.procurement.dto.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DashboardResponse(String currentMonth, String targetMonth, InventorySummary inventory,
                                 List<StockBySize> stockBySize, List<Shortage> shortages, Long projectedStockQty,
                                 DemandForecast demandForecast, LatestResult<CostForecastResponse> currentMonthCost,
                                 LatestResult<CostForecastResponse> targetMonthCost) {
    public record StockBySize(String sizeClass, long onHandQty, long safetyQty) {}
    public record Shortage(UUID boxSpecId, String code, String name, long onHandQty, long safetyQty,
                           long shortageQty, Integer daysToStockout) {}
    public record DemandForecast(LatestResult<ProposalResponse> proposal, Long totalForecastUsageQty,
                                  List<DemandBySize> bySize) {}
    public record DemandBySize(String sizeClass, long forecastUsageQty, BigDecimal sharePct) {}
}
