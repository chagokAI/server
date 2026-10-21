package com.skala.chagok.job;

import com.skala.chagok.domain.job.config.JobSchedulingConfig;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.scheduling.*;
import com.skala.chagok.domain.job.service.*;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.awaitility.Awaitility.await;

class JobSchedulingTests {
    @Test void springTimerActuallyEnqueuesBothMonthsWithoutKafka() {
        UUID id = UUID.randomUUID();
        var warehouses = mock(WarehouseRepository.class);
        when(warehouses.findIds(any())).thenReturn(List.of(id));
        var commands = mock(JobCommandService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("timer-test",
                    Map.of("chagok.jobs.scheduling-enabled", "true", "chagok.jobs.cron", "*/1 * * * * *")));
            context.registerBean(WarehouseRepository.class, () -> warehouses);
            context.registerBean(JobCommandService.class, () -> commands);
            context.registerBean(ChagokProperties.class, () -> new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
                    new ChagokProperties.Inventory(3, 14), new ChagokProperties.BoxSize(10, 50)));
            context.register(JobSchedulingConfig.class, ForecastScheduler.class);
            context.refresh();
            YearMonth month = YearMonth.now(ZoneId.of("Asia/Seoul"));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                verify(commands, atLeastOnce()).submit(id, month.toString(), AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.SCHEDULED);
                verify(commands, atLeastOnce()).submit(id, month.plusMonths(1).toString(), AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.SCHEDULED);
            });
        }
    }

    @Test void pollingWorkerInvokesSameExecutionService() {
        var execution = mock(JobExecutionService.class);
        new JobWorker(execution).poll();
        verify(execution).runNext();
    }
}
