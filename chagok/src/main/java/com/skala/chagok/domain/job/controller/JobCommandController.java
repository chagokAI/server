package com.skala.chagok.domain.job.controller;

import com.skala.chagok.domain.job.dto.*;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.service.JobCommandService;
import com.skala.chagok.global.response.*;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/warehouses/{warehouseId}")
public class JobCommandController {
    private final JobCommandService service;
    private final ApiResponses responses;

    @PostMapping("/ai-jobs")
    public ResponseEntity<ApiResponse<JobResponse>> create(@PathVariable UUID warehouseId,
                                                            @Valid @RequestBody JobCreateRequest request) {
        return accepted(warehouseId, service.submit(warehouseId, request.targetMonth(), request.type(), AnalysisJob.Trigger.MANUAL));
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<ApiResponse<JobResponse>> retry(@PathVariable UUID warehouseId, @PathVariable UUID jobId,
                                                           @Valid @RequestBody JobRetryRequest request) {
        return accepted(warehouseId, service.retry(warehouseId, jobId, request.expectedVersion()));
    }

    private ResponseEntity<ApiResponse<JobResponse>> accepted(UUID warehouseId, JobResponse job) {
        URI location = URI.create("/api/v1/warehouses/" + warehouseId + "/jobs/" + job.id());
        return ResponseEntity.accepted().location(location).body(responses.of(job, job.dataAsOf(), null));
    }
}
