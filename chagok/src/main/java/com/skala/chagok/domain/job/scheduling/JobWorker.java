package com.skala.chagok.domain.job.scheduling;

import com.skala.chagok.domain.job.service.JobExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "chagok.jobs.worker-enabled", havingValue = "true", matchIfMissing = true)
public class JobWorker {
    private final JobExecutionService execution;

    @Scheduled(fixedDelayString = "${chagok.jobs.poll-delay:1000}")
    public void poll() { execution.runNext(); }
}
