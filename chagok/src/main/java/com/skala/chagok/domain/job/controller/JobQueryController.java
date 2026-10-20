package com.skala.chagok.domain.job.controller;

import com.skala.chagok.domain.job.dto.JobResponse;
import com.skala.chagok.domain.job.service.JobQueryService;
import com.skala.chagok.global.response.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/warehouses/{warehouseId}/jobs")
public class JobQueryController {
    private final JobQueryService service;
    private final ApiResponses responses;

    @GetMapping("/{jobId}")
    public ApiResponse<JobResponse> get(@PathVariable UUID warehouseId, @PathVariable UUID jobId) {
        JobResponse job = service.get(warehouseId, jobId);
        return responses.of(job, job.dataAsOf(), null);
    }
}
