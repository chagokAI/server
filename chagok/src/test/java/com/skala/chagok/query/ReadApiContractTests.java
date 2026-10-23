package com.skala.chagok.query;

import com.skala.chagok.domain.dashboard.controller.DashboardQueryController;
import com.skala.chagok.domain.dashboard.dto.SceneResponse;
import com.skala.chagok.domain.dashboard.service.*;
import com.skala.chagok.domain.job.controller.JobQueryController;
import com.skala.chagok.domain.job.dto.JobResponse;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.service.JobQueryService;
import com.skala.chagok.domain.notification.controller.NotificationQueryController;
import com.skala.chagok.domain.notification.service.NotificationQueryService;
import com.skala.chagok.domain.procurement.controller.ProcurementQueryController;
import com.skala.chagok.domain.procurement.dto.*;
import com.skala.chagok.domain.procurement.service.ProcurementQueryService;
import com.skala.chagok.domain.purchase.controller.PurchaseOrderQueryController;
import com.skala.chagok.domain.purchase.service.PurchaseOrderQueryService;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.response.ApiResponses;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReadApiContractTests {
    private final UUID warehouseId = UUID.randomUUID();
    private final ProcurementQueryService procurement = mock(ProcurementQueryService.class);
    private final DashboardQueryService dashboards = mock(DashboardQueryService.class);
    private final ObservationQueryService observations = mock(ObservationQueryService.class);
    private final PurchaseOrderQueryService orders = mock(PurchaseOrderQueryService.class);
    private final NotificationQueryService notifications = mock(NotificationQueryService.class);
    private final JobQueryService jobs = mock(JobQueryService.class);
    private final ApiResponses responses = new ApiResponses(new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
            new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50)));
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ProcurementQueryController(procurement, responses),
                new DashboardQueryController(dashboards, observations, responses),
                new PurchaseOrderQueryController(orders, responses), new NotificationQueryController(notifications, responses),
                new JobQueryController(jobs, responses)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private String path(String suffix) { return "/api/v1/warehouses/" + warehouseId + suffix; }

    @Test void latestWithNoResultStillReturnsJobAndNotGenerated() throws Exception {
        var job = JobResponse.from(AnalysisJob.builder().id(UUID.randomUUID()).targetMonth("2026-11")
                .type(AnalysisJob.Type.PROCUREMENT_AND_COST).status(AnalysisJob.Status.RUNNING).version(0).build());
        when(procurement.latestProposal(warehouseId, "2026-11")).thenReturn(LatestResult.of("2026-11", null, job));
        mvc.perform(get(path("/procurement-proposals/latest")).param("targetMonth", "2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dataStatus").value("NOT_GENERATED"))
                .andExpect(jsonPath("$.data.result").isEmpty()).andExpect(jsonPath("$.data.latestJob.status").value("RUNNING"))
                .andExpect(jsonPath("$.meta.refreshAfterSeconds").value(300));
    }

    @Test void failedNewJobDoesNotHideLastSuccessfulCostResult() throws Exception {
        Instant cutoff = Instant.parse("2026-10-19T00:00:00Z");
        var cost = new CostForecastResponse(UUID.randomUUID(), UUID.randomUUID(), "2026-11", cutoff,
                cutoff.plusSeconds(60), "KRW", false, new CostForecastResult(13350000L, 3420000L, 10566000L,
                null, null, List.of(), List.of()));
        var job = JobResponse.from(AnalysisJob.builder().status(AnalysisJob.Status.FAILED)
                .type(AnalysisJob.Type.PROCUREMENT_AND_COST).failureCode("MODEL_UNAVAILABLE").version(1).build());
        when(procurement.latestCost(warehouseId, "2026-11")).thenReturn(LatestResult.of("2026-11", cost, job));
        mvc.perform(get(path("/cost-forecasts/latest")).param("targetMonth", "2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dataStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.result.forecast.purchaseCostKrw").value(10566000))
                .andExpect(jsonPath("$.data.latestJob.retryable").value(true))
                .andExpect(jsonPath("$.meta.dataAsOf").value("2026-10-19T09:00:00+09:00"));
    }

    @Test void sceneHasStableIdentityAndNoFabricatedPlacement() throws Exception {
        var observation = new SceneResponse.Observation("evt-1", "evt-1", Instant.parse("2026-10-19T00:00:00Z"),
                "SUCCEEDED", null, null, null, null, false, "CATALOG", null);
        when(observations.scene(warehouseId, 100)).thenReturn(new SceneResponse(List.of(observation), 100, observation.observedAt(), 2));
        mvc.perform(get(path("/simulation-state"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].boxId").value("evt-1"))
                .andExpect(jsonPath("$.data.items[0].placement").isEmpty())
                .andExpect(jsonPath("$.data.items[0].placementAvailable").value(false))
                .andExpect(jsonPath("$.meta.pendingEventCount").value(2));
    }

    @Test void sceneInRangeReturnsMeasuredDimensionsInTimeOrder() throws Exception {
        var measured = new SceneResponse.Measurement("known", "measured", 287, 220, 112, 425L, null, null, "IMPORTED_SAMPLE");
        var observation = new SceneResponse.Observation("evt-2", "evt-2", Instant.parse("2026-09-01T00:10:00Z"),
                "SUCCEEDED", null, null, null, null, false, "CATALOG", measured);
        var from = Instant.parse("2026-08-31T23:00:00Z");
        var to = Instant.parse("2026-09-01T11:00:00Z");
        when(observations.scene(warehouseId, 300, from, to, null, null, false))
                .thenReturn(new SceneResponse(List.of(observation), 300, observation.observedAt(), 0));
        mvc.perform(get(path("/simulation-state")).param("limit", "300")
                        .param("from", "2026-09-01T08:00:00+09:00").param("to", "2026-09-01T20:00:00+09:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].measurement.widthMm").value(287))
                .andExpect(jsonPath("$.data.items[0].measurement.sizeType").value("measured"));
        verify(observations, never()).scene(warehouseId, 300);
    }

    @Test void sceneCursorRequestReturnsNextIdentityAndAvailableRange() throws Exception {
        var from = Instant.parse("2026-10-23T00:00:00Z");
        var to = Instant.parse("2026-10-24T00:00:00Z");
        var after = Instant.parse("2026-10-23T01:00:00Z");
        var observation = new SceneResponse.Observation("evt-2", "evt-2", after,
                "PENDING", null, null, null, null, false, "CATALOG", null);
        when(observations.scene(warehouseId, 1, from, to, after, "evt-1", true))
                .thenReturn(new SceneResponse(List.of(observation), 1, after, 2,
                        new SceneResponse.Cursor(after, "evt-2"), true,
                        new SceneResponse.AvailableRange(from, to, null, null, 3, 0)));

        mvc.perform(get(path("/simulation-state")).param("limit", "1")
                        .param("from", from.toString()).param("to", to.toString())
                        .param("afterObservedAt", after.toString()).param("afterEventId", "evt-1")
                        .param("includeRange", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eventId").value("evt-2"))
                .andExpect(jsonPath("$.data.nextCursor.eventId").value("evt-2"))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.availableRange.eventCount").value(3))
                .andExpect(jsonPath("$.meta.pendingEventCount").value(2));
    }

    @Test void rejectsOutOfRangeLimitBeforeCallingService() throws Exception {
        mvc.perform(get(path("/simulation-state")).param("limit", "501"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(observations);
    }

    @Test void rejectsUnknownMetricAndMissingDates() throws Exception {
        mvc.perform(get(path("/metrics")).param("metric", "SQL_TEXT").param("from", "2026-10-01").param("to", "2026-10-19"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path("/metrics")).param("metric", "OBSERVATION_COUNT"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fieldErrors[0].field").value("from"));
        verifyNoInteractions(observations);
    }

    @Test void requiresMonthForLatestAndValidUuidForDetails() throws Exception {
        mvc.perform(get(path("/procurement-proposals/latest"))).andExpect(status().isBadRequest());
        mvc.perform(get(path("/procurement-proposals/not-a-uuid"))).andExpect(status().isBadRequest());
        verifyNoInteractions(procurement);
    }

    @Test void scopedMissingResourceReturnsCanonicalError() throws Exception {
        UUID id = UUID.randomUUID();
        when(procurement.proposal(warehouseId, id)).thenThrow(BusinessException.notFound("발주 제안서"));
        mvc.perform(get(path("/procurement-proposals/" + id)).header("X-Request-Id", "contract-1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.requestId").value("contract-1"));
    }

    @Test void validatesNotificationAndHistoryParameters() throws Exception {
        mvc.perform(get(path("/notifications")).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(path("/notifications")).param("category", "UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get(path("/purchase-orders")).param("from", "2026-10-01")).andExpect(status().isBadRequest());
        verifyNoInteractions(notifications, orders);
    }
}
