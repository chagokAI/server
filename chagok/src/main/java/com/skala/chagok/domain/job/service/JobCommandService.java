package com.skala.chagok.domain.job.service;

import com.skala.chagok.domain.job.dto.*;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.AnalysisJobRepository;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.*;
import com.skala.chagok.global.web.QueryValues;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class JobCommandService {
    private final AnalysisJobRepository jobs;
    private final WarehouseRepository warehouses;
    private final ChagokProperties properties;
    private final Clock jobClock;

    @Transactional
    public JobResponse submit(UUID warehouseId, String targetMonth, AnalysisJob.Type type, AnalysisJob.Trigger trigger) {
        String month = validMonth(targetMonth);
        var warehouse = warehouses.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        var existing = jobs.findByActiveKey(AnalysisJob.key(warehouseId, month, type));
        if (existing.isPresent()) return JobResponse.from(existing.get());
        return JobResponse.from(jobs.saveAndFlush(AnalysisJob.pending(warehouse, month, type, trigger, jobClock.instant())));
    }

    @Transactional
    public JobResponse retry(UUID warehouseId, UUID jobId, Integer expectedVersion) {
        var warehouse = warehouses.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        var job = jobs.findByIdAndWarehouseId(jobId, warehouseId).orElseThrow(() -> BusinessException.notFound("분석 작업"));
        if (job.getRetryJobId() != null) return JobResponse.from(jobs.findByIdAndWarehouseId(job.getRetryJobId(), warehouseId).orElseThrow());
        if (!Objects.equals(expectedVersion, job.getVersion())) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        if (!Set.of(AnalysisJob.Status.FAILED, AnalysisJob.Status.PUBLISH_FAILED, AnalysisJob.Status.TIMED_OUT).contains(job.getStatus()))
            throw new BusinessException(ErrorCode.INVALID_STATE, "실패하거나 기한이 지난 작업만 재시도할 수 있습니다.");
        validMonth(job.getTargetMonth());
        var active = jobs.findByActiveKey(AnalysisJob.key(warehouseId, job.getTargetMonth(), job.getType()));
        var next = active.orElseGet(() -> jobs.saveAndFlush(AnalysisJob.pending(warehouse, job.getTargetMonth(),
                job.getType(), AnalysisJob.Trigger.RETRY, jobClock.instant(), job.getId())));
        job.linkRetry(next.getId());
        jobs.flush();
        return JobResponse.from(next);
    }

    private String validMonth(String value) {
        YearMonth current = YearMonth.now(jobClock.withZone(properties.timezone()));
        YearMonth month = QueryValues.month(value, current.plusMonths(1));
        if (month.isBefore(current) || month.isAfter(current.plusMonths(12)))
            throw BusinessException.invalidField("targetMonth", "이번 달부터 12개월 뒤까지 지정할 수 있습니다.");
        return month.toString();
    }
}
