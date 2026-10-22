package com.skala.chagok.domain.dashboard.controller;

import com.skala.chagok.domain.dashboard.dto.*;
import com.skala.chagok.domain.dashboard.service.*;
import com.skala.chagok.global.response.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}")
public class DashboardQueryController {
    private final DashboardQueryService dashboards;
    private final ObservationQueryService observations;
    private final ApiResponses responses;

    @GetMapping("/dashboard")
    public ApiResponse<DashboardResponse> dashboard(@PathVariable UUID warehouseId,
                                                     @RequestParam(required = false) String targetMonth) {
        return responses.of(dashboards.get(warehouseId, targetMonth));
    }

    @GetMapping("/simulation-state")
    public ApiResponse<SceneResponse> scene(@PathVariable UUID warehouseId,
                                             @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
                                             @RequestParam(required = false) Instant from,
                                             @RequestParam(required = false) Instant to,
                                             @RequestParam(required = false) Instant afterObservedAt,
                                             @RequestParam(required = false) String afterEventId,
                                             @RequestParam(defaultValue = "false") boolean includeRange) {
        var scene = from == null && to == null && afterObservedAt == null && afterEventId == null && !includeRange
                ? observations.scene(warehouseId, limit)
                : observations.scene(warehouseId, limit, from, to, afterObservedAt, afterEventId, includeRange);
        return responses.of(scene, null, scene.pendingEventCount());
    }

    @GetMapping("/metrics")
    public ApiResponse<MetricSeries> metrics(@PathVariable UUID warehouseId,
                                              @RequestParam MetricSeries.Metric metric,
                                              @RequestParam LocalDate from, @RequestParam LocalDate to,
                                              @RequestParam(defaultValue = "day") MetricSeries.Bucket bucket,
                                              @RequestParam(required = false) UUID boxSpecId) {
        return responses.of(observations.metrics(warehouseId, metric, from, to, bucket, boxSpecId));
    }
}
