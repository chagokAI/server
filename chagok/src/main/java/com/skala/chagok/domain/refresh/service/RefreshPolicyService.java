package com.skala.chagok.domain.refresh.service;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.repository.AnalysisJobRepository;
import com.skala.chagok.domain.refresh.dto.RefreshPolicyResponse;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import com.skala.chagok.global.error.BusinessException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 새로고침 정책. 값은 모두 설정 파일에서 읽으며, 정기 배치는 ForecastScheduler와 같은 설정 키를 읽어
 * 실제 스케줄과 어긋나지 않게 한다.
 */
@Service
@Transactional(readOnly = true)
public class RefreshPolicyService {

    private final WarehouseRepository warehouseRepository;
    private final AnalysisJobRepository jobRepository;
    private final ChagokProperties properties;
    private final Clock jobClock;
    private final boolean schedulingEnabled;
    private final String cron;

    public RefreshPolicyService(WarehouseRepository warehouseRepository, AnalysisJobRepository jobRepository,
                                ChagokProperties properties, Clock jobClock,
                                @Value("${chagok.jobs.scheduling-enabled:true}") boolean schedulingEnabled,
                                @Value("${chagok.jobs.cron:0 */5 * * * *}") String cron) {
        this.warehouseRepository = warehouseRepository;
        this.jobRepository = jobRepository;
        this.properties = properties;
        this.jobClock = jobClock;
        this.schedulingEnabled = schedulingEnabled;
        this.cron = cron;
    }

    public RefreshPolicyResponse getPolicy(UUID warehouseId) {
        if (!warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("센터");
        }
        ZoneId zone = properties.timezone();
        RefreshPolicyResponse.LastRun lastRun = jobRepository
                .findFirstByWarehouseIdAndTriggerOrderByCreatedAtDescIdDesc(warehouseId, AnalysisJob.Trigger.SCHEDULED)
                .map(job -> new RefreshPolicyResponse.LastRun(job.getId(), job.getTargetMonth(), job.getStatus(),
                        at(job.getCreatedAt(), zone), at(job.getCompletedAt(), zone)))
                .orElse(null);
        return new RefreshPolicyResponse(properties.refreshAfterSeconds(), zone.getId(),
                new RefreshPolicyResponse.AnalysisSchedule(schedulingEnabled, cron, nextRunAt(zone), lastRun));
    }

    /** ForecastScheduler와 같이 센터 시간대 기준으로 cron의 다음 실행 시각을 계산한다. */
    private OffsetDateTime nextRunAt(ZoneId zone) {
        if (!schedulingEnabled) {
            return null;
        }
        ZonedDateTime next = CronExpression.parse(cron).next(ZonedDateTime.now(jobClock).withZoneSameInstant(zone));
        return next == null ? null : next.toOffsetDateTime();
    }

    private static OffsetDateTime at(Instant instant, ZoneId zone) {
        return instant == null ? null : instant.atZone(zone).toOffsetDateTime();
    }
}
