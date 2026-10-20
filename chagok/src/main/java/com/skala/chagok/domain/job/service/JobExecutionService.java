package com.skala.chagok.domain.job.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j @Service @RequiredArgsConstructor
public class JobExecutionService {
    private final JobQueueService queue;
    private final JobGenerationService generator;

    public boolean runNext() {
        queue.expireStale();
        var claim = queue.claimNext();
        if (claim.isEmpty()) return false;
        try {
            generator.generate(claim.get());
        } catch (JobCalculationException e) {
            queue.fail(claim.get(), e.code(), e.getMessage());
        } catch (Exception e) {
            log.error("분석 작업 실행 실패: jobId={}", claim.get().id(), e);
            queue.fail(claim.get(), "EXECUTION_FAILED", "작업 실행에 실패했습니다. 잠시 후 재시도하세요.");
        }
        return true;
    }
}
