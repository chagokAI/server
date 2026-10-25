package com.skala.chagok.live;

import com.skala.chagok.domain.event.LiveEventIds;
import com.skala.chagok.domain.inventory.service.InventoryPackingService;
import com.skala.chagok.global.config.ChagokProperties;
import java.sql.Timestamp;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연속 재생 중 쌓이는 라이브 원본 정리. 하루 수십만 건이라 일별 집계(DailyShipmentArchive, 00:15)가 끝난 날의
 * 라이브 출고(live-)와 그 포장 차감 기록만 보관 일수(raw-keep-days)가 지나면 지운다.
 * 라이브가 아닌 원본(과거 이력·실제 촬영 출고)과 입고·실사 기록, 접수 대기(PENDING)는 건드리지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "chagok.live.continuous.enabled", havingValue = "true")
public class LiveContinuousCleaner {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ChagokProperties properties;
    private final int keepDays;

    public LiveContinuousCleaner(JdbcTemplate jdbc, TransactionTemplate transactions, ChagokProperties properties,
            @Value("${chagok.live.continuous.raw-keep-days:2}") int keepDays) {
        this.jdbc = jdbc; this.transactions = transactions; this.properties = properties;
        this.keepDays = Math.max(1, keepDays);
    }

    @Scheduled(cron = "${chagok.live.continuous.cleanup-cron:0 40 0 * * *}", zone = "${chagok.timezone:Asia/Seoul}")
    public void cleanup() {
        LocalDate keepFrom = LocalDate.now(properties.timezone()).minusDays(keepDays - 1L);
        var before = Timestamp.from(keepFrom.atStartOfDay(properties.timezone()).toInstant());
        transactions.executeWithoutResult(tx -> {
            // 여러 서버가 동시에 정리해도 한 번만 실행한다.
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(724092)", Boolean.class))) return;
            int items = jdbc.update("""
                    delete from item_event e where e.event_id like ? and e.status <> 'PENDING' and e.occurred_at < ?
                      and exists (select 1 from item_event_daily d where d.warehouse_id = e.warehouse_id and d.source = 'live'
                                  and d.day = cast(e.occurred_at at time zone ? as date))
                    """, LiveEventIds.PREFIX + "%", before, properties.timezone().getId());
            int movements = jdbc.update("delete from inventory_movement where type = 'CONSUMPTION' and memo like ? and created_at < ?",
                    InventoryPackingService.MOVEMENT_MEMO_PREFIX + LiveEventIds.PREFIX + "%", before);
            log.info("라이브 원본 정리: {} 이전, 출고 {}건, 포장 차감 기록 {}건", keepFrom, items, movements);
        });
    }
}
