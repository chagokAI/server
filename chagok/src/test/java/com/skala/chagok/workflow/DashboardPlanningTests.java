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
import com.skala.chagok.domain.procurement.entity.ProcurementProposalItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.math.BigDecimal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardPlanningTests {
    // 2026-10-26 14:00 KST: 다음 달 1일까지 5일 10시간(5.42일)
    private static final Clock OCT_26_14H = Clock.fixed(Instant.parse("2026-10-26T05:00:00Z"), ZoneId.of("UTC"));

    @Test void projectsNextMonthEndStockFromTheStartOfNextMonthNotFromToday() {
        var warehouse = UUID.randomUUID();
        var box = BoxSpec.builder().id(UUID.randomUUID()).code("S-1").sizeClass('S').onHandQty(100).safetyDays(7).build();
        var boxes = mock(BoxSpecRepository.class);
        when(boxes.findByWarehouseIdAndIsActiveTrue(warehouse)).thenReturn(List.of(box));
        var inventory = mock(InventoryService.class);
        when(inventory.summarize(warehouse))
                .thenReturn(new InventorySummary(100L, 1, 0, List.of(), null, null, null, 14, null, null, 0));
        var forecasts = mock(ProcurementQueryService.class);
        var summary = Map.<String, Object>of("clientView", Map.of(), "forecast", Map.of("forecast", 300),
                "usagePlan", List.of(Map.of("code", "S-1", "dims", List.of(100,100,100), "unitPrice", 100, "expectedUsage", 300)));
        var item = ProcurementProposalItem.builder().boxSpec(box).proposedQty(270).forecastUsageQty(300).safetyQty(70)
                .onHandQty(100).unitPriceKrw(100).build();
        var proposal = ProposalResponse.from(ProcurementProposal.builder().id(UUID.randomUUID()).targetMonth("2026-11")
                .summary(summary).build(), List.of(item));
        when(forecasts.latestProposal(warehouse, "2026-11", "AUTO")).thenReturn(LatestResult.of("2026-11", proposal, null));
        when(forecasts.latestCost(eq(warehouse), anyString())).thenAnswer(call -> LatestResult.of(call.getArgument(1), null, null));
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        // 첫 조회 = 다른 미입고 발주 40, 둘째 = 이 제안 발주 중 이미 입고 20
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(40L, 20L);
        var properties = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300, new ChagokProperties.Inventory(3,14), new ChagokProperties.BoxSize(10,50));
        var result = new DashboardQueryService(boxes, inventory, forecasts, mock(WarehouseQueryGuard.class), properties,
                new BoxSizeClassifier(properties), jdbc, OCT_26_14H).get(warehouse, "2026-11");
        var p = result.projectedStock();
        assertThat(p.remainingDays()).isEqualByComparingTo(new BigDecimal("5.42"));
        assertThat(p.consumeBeforeQty()).isEqualTo(54);          // 300 / 30일 × 5.42일
        assertThat(p.inboundQty()).isEqualTo(40);
        assertThat(p.orderQty()).isEqualTo(250);                 // 제안 270 − 이미 입고 20
        assertThat(p.endQty()).isEqualTo(100 - 54 + 40 + 250 - 300);
        assertThat(result.projectedStockQty()).isEqualTo(p.endQty());
        assertThat(p.safetyQty()).isEqualTo(70);                 // 300 × 7일 / 30일
        assertThat(result.currentMonthCost().result()).isNull();  // 이번 달 제안서·리포트 없음 → 참고 계산 그대로
        // 크기별: 입고 예정·입고된 제안 발주 크기 조회(jdbc.query)는 비어 있으므로 S = 100 − 54 + 270 − 300
        var size = p.bySize().getFirst();
        assertThat(size.sizeClass()).isEqualTo("S");
        assertThat(size.orderQty()).isEqualTo(270);
        assertThat(size.endQty()).isEqualTo(100 - 54 + 270 - 300);
    }

    @Test void reportsThisMonthsCostFromThisMonthsAgentProposal() {
        var warehouse = UUID.randomUUID();
        var boxes = mock(BoxSpecRepository.class);
        when(boxes.findByWarehouseIdAndIsActiveTrue(warehouse)).thenReturn(List.of());
        var inventory = mock(InventoryService.class);
        when(inventory.summarize(warehouse)).thenReturn(new InventorySummary(0L, 0, 0, List.of(), null, null, null, 14, null, null, 0));
        var forecasts = mock(ProcurementQueryService.class);
        var summary = Map.<String, Object>of("clientView", Map.of(), "forecast", Map.of("forecast", 40),
                "simulation", Map.of("monthly_saving", 500),
                "usagePlan", List.of(Map.of("code", "S-1", "dims", List.of(100,100,100), "unitPrice", 100, "expectedUsage", 40)));
        var octBox = BoxSpec.builder().id(UUID.randomUUID()).code("S-1").sizeClass('S').build();
        var octItem = ProcurementProposalItem.builder().boxSpec(octBox).proposedQty(50).forecastUsageQty(40).unitPriceKrw(100).build();
        var october = ProposalResponse.from(ProcurementProposal.builder().targetMonth("2026-10").summary(summary).build(), List.of(octItem));
        when(forecasts.latestProposal(warehouse, "2026-10", "AUTO")).thenReturn(LatestResult.of("2026-10", october, null));
        when(forecasts.latestProposal(warehouse, "2026-11", "AUTO")).thenReturn(LatestResult.of("2026-11", null, null));
        when(forecasts.latestCost(eq(warehouse), anyString())).thenAnswer(call -> LatestResult.of(call.getArgument(1), null, null));
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        // 본부 리포트 10월: 25일치 실제 절감 2,500원 → 30일 기준 3,000원
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("saving", new BigDecimal("2500"), "days", new BigDecimal("25"))));
        var properties = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300, new ChagokProperties.Inventory(3,14), new ChagokProperties.BoxSize(10,50));
        var result = new DashboardQueryService(boxes, inventory, forecasts, mock(WarehouseQueryGuard.class), properties,
                new BoxSizeClassifier(properties), jdbc, OCT_26_14H).get(warehouse, "2026-11");
        var cost = result.currentMonthCost().result().forecast();
        assertThat(cost.packagingCostKrw()).isEqualTo(4000);       // 10월 제안서 사용 계획 40 × 100원
        assertThat(cost.packagingSavingsKrw()).isEqualTo(3000);    // 리포트 실제 절감(시뮬레이션 500원이 아님)
        // 11월 제안서가 없으면 10월 제안서로 대신 그린다(임시, 기준 문구 표시)
        assertThat(result.demandForecast().totalForecastUsageQty()).isEqualTo(40);
        assertThat(result.planMetrics().basis()).startsWith("10월 제안서 기준(11월 제안서 생성 전 임시)");
        assertThat(result.targetMonthCost().result().forecast().purchaseCostKrw()).isEqualTo(5000);   // 10월 주문 50 × 100원
        assertThat(result.targetMonthCost().result().forecast().insights().getFirst()).startsWith("10월 제안서 기준");
    }

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
                new BoxSizeClassifier(properties), mock(NamedParameterJdbcTemplate.class), OCT_26_14H).get(warehouse, "2026-11");
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
