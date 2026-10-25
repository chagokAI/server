package com.skala.chagok.workflow;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.box.service.BoxSizeClassifier;
import com.skala.chagok.domain.dashboard.service.DashboardQueryService;
import com.skala.chagok.domain.inventory.dto.*;
import com.skala.chagok.domain.inventory.service.InventoryService;
import com.skala.chagok.domain.procurement.dto.*;
import com.skala.chagok.domain.procurement.entity.ProcurementProposal;
import com.skala.chagok.domain.procurement.service.ProcurementQueryService;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.time.ZoneId;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardPlanningTests {
    @Test void derivesSafetyDaysFromTheFullForecastAndKeepsShortageAndUnknownCountsConsistent() {
        var warehouse = UUID.randomUUID();
        var box = BoxSpec.builder().id(UUID.randomUUID()).code("S-1").sizeClass('S').onHandQty(1).safetyDays(7).build();
        var boxes = mock(BoxSpecRepository.class);
        when(boxes.findByWarehouseIdAndIsActiveTrue(warehouse)).thenReturn(List.of(box));
        var inventory = mock(InventoryService.class);
        when(inventory.summarize(warehouse))
                .thenReturn(new InventorySummary(1L, 1, 0, List.of(), null, null, null, 14, null, null, 1));
        var forecasts = mock(ProcurementQueryService.class);
        var summary = Map.<String, Object>of("clientView", Map.of(), "forecast", Map.of("forecast", 30),
                "simulation", Map.of("monthly_saving", 200),
                "usagePlan", List.of(Map.of("code", "S-1", "dims", List.of(100,100,100), "unitPrice", 100, "expectedUsage", 30)));
        var proposal = ProposalResponse.from(ProcurementProposal.builder().targetMonth("2026-11").summary(summary).build(), List.of());
        when(forecasts.latestProposal(warehouse, "2026-11", "AUTO")).thenReturn(LatestResult.of("2026-11", proposal, null));
        when(forecasts.latestCost(eq(warehouse), anyString())).thenAnswer(call -> LatestResult.of(call.getArgument(1), null, null));
        var properties = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300, new ChagokProperties.Inventory(3,14), new ChagokProperties.BoxSize(10,50));
        var result = new DashboardQueryService(boxes, inventory, forecasts, mock(WarehouseQueryGuard.class), properties,
                new BoxSizeClassifier(properties)).get(warehouse, "2026-11");
        assertThat(result.demandForecast().totalForecastUsageQty()).isEqualTo(30);
        assertThat(result.demandForecast().bySize().getFirst().forecastUsageQty()).isEqualTo(30);
        assertThat(result.planMetrics().estimatedBoxCostKrw()).isEqualTo(3000);
        assertThat(result.planMetrics().monthlySavingsKrw()).isEqualTo(200);
        assertThat(result.planMetrics().unknownSafetySpecCount()).isZero();
        assertThat(result.inventory().unknownSpecCount()).isZero();
        assertThat(result.inventory().shortageSpecCount()).isEqualTo(1);
        assertThat(result.shortages().getFirst().safetyQty()).isEqualTo(7);
        assertThat(result.shortages().getFirst().shortageQty()).isEqualTo(6);
        assertThat(box.getSafetyQty()).isNull();
    }
}
