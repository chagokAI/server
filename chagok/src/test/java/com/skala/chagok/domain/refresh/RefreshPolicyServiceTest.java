package com.skala.chagok.domain.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.AnalysisJobRepository;
import com.skala.chagok.domain.refresh.service.RefreshPolicyService;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RefreshPolicyServiceTest {

    private final UUID warehouseId = UUID.randomUUID();
    private final WarehouseRepository warehouses = mock(WarehouseRepository.class);
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private final ChagokProperties properties = new ChagokProperties(ZoneId.of("Asia/Seoul"), 300,
            new ChagokProperties.Inventory(3, 14));
    /** 2026-10-21 10:02 (Asia/Seoul) */
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T01:02:00Z"), ZoneOffset.UTC);

    private RefreshPolicyService service(boolean schedulingEnabled) {
        when(warehouses.existsById(warehouseId)).thenReturn(true);
        return new RefreshPolicyService(warehouses, jobs, properties, clock, schedulingEnabled, "0 */5 * * * *");
    }

    @Test void returnsTheSharedRefreshIntervalAndTheNextCronRunInTheCenterTimezone() {
        var policy = service(true).getPolicy(warehouseId);
        assertThat(policy.refreshAfterSeconds()).isEqualTo(300);
        assertThat(policy.timezone()).isEqualTo("Asia/Seoul");
        assertThat(policy.analysisSchedule().nextRunAt()).hasToString("2026-10-21T10:05+09:00");
        assertThat(policy.analysisSchedule().lastRun()).isNull();
    }

    @Test void reportsTheLatestScheduledJobAndNoNextRunWhenSchedulingIsOff() {
        var job = AnalysisJob.builder().id(UUID.randomUUID()).targetMonth("2026-10")
                .status(AnalysisJob.Status.SUCCEEDED).createdAt(Instant.parse("2026-10-21T01:00:00Z"))
                .completedAt(Instant.parse("2026-10-21T01:00:30Z")).build();
        when(jobs.findFirstByWarehouseIdAndTriggerOrderByCreatedAtDescIdDesc(warehouseId, AnalysisJob.Trigger.SCHEDULED))
                .thenReturn(Optional.of(job));
        var schedule = service(false).getPolicy(warehouseId).analysisSchedule();
        assertThat(schedule.enabled()).isFalse();
        assertThat(schedule.nextRunAt()).isNull();
        assertThat(schedule.lastRun().status()).isEqualTo(AnalysisJob.Status.SUCCEEDED);
        assertThat(schedule.lastRun().completedAt()).hasToString("2026-10-21T10:00:30+09:00");
    }

    @Test void unknownWarehouseIsNotFound() {
        var service = new RefreshPolicyService(warehouses, jobs, properties, clock, true, "0 */5 * * * *");
        assertThatThrownBy(() -> service.getPolicy(UUID.randomUUID())).isInstanceOf(BusinessException.class);
    }
}
