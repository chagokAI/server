package com.skala.chagok.domain.job.service;

import com.skala.chagok.domain.job.dto.JobResponse;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.AnalysisJobRepository;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.web.WarehouseQueryGuard;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class JobQueryService {
    private final AnalysisJobRepository jobs;
    private final WarehouseQueryGuard warehouses;

    public JobResponse get(UUID warehouseId, UUID jobId) {
        warehouses.require(warehouseId);
        return jobs.findByIdAndWarehouseId(jobId, warehouseId).map(JobResponse::from)
                .orElseThrow(() -> BusinessException.notFound("분석 작업"));
    }

    public JobResponse latest(UUID warehouseId, String targetMonth) {
        return jobs.findFirstByWarehouseIdAndTargetMonthAndTypeOrderByCreatedAtDescIdDesc(
                warehouseId, targetMonth, AnalysisJob.Type.PROCUREMENT_AND_COST).map(JobResponse::from).orElse(null);
    }
}
