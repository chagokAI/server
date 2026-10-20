package com.skala.chagok.domain.job.scheduling;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.service.JobCommandService;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.config.ChagokProperties;
import java.time.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j @Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "chagok.jobs.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ForecastScheduler {
    private final WarehouseRepository warehouses;
    private final JobCommandService commands;
    private final ChagokProperties properties;
    private final Clock jobClock;

    @Scheduled(cron = "${chagok.jobs.cron:0 */5 * * * *}", zone = "${chagok.timezone:Asia/Seoul}")
    public void enqueue() {
        YearMonth current = YearMonth.now(jobClock.withZone(properties.timezone()));
        // 전체 센터 엔티티 대신 페이지별 ID만 읽는다.
        for (int page = 0; ; page++) {
            var ids = warehouses.findIds(org.springframework.data.domain.PageRequest.of(page, 100));
            for (var id : ids) {
                for (var month : new YearMonth[]{current, current.plusMonths(1)}) {
                    try { commands.submit(id, month.toString(), AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.SCHEDULED); }
                    catch (Exception e) { log.error("정기 작업 접수 실패: warehouseId={}, targetMonth={}", id, month, e); }
                }
            }
            if (ids.size() < 100) break;
        }
    }
}
