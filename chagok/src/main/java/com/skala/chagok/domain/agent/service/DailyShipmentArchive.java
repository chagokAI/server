package com.skala.chagok.domain.agent.service;

import com.skala.chagok.global.config.ChagokProperties;
import java.time.LocalDate;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 과거 출고 집계와 원본을 연결한다. 원본 삭제는 하지 않아 거제의 비교 시뮬레이션 자료도 보존한다. */
@Slf4j @Component @RequiredArgsConstructor
public class DailyShipmentArchive implements ApplicationRunner {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ChagokProperties properties;
    private volatile boolean ready;

    @Override
    public void run(ApplicationArguments args) {
        new ResourceDatabasePopulator(new ClassPathResource("db/init/item_event_daily.sql")).execute(dataSource);
        ready = true;
    }

    @Scheduled(cron = "${chagok.shipments.archive-cron:0 15 0 * * *}", zone = "${chagok.timezone:Asia/Seoul}")
    public void rollupYesterday() {
        if (!ready) return;
        LocalDate day = LocalDate.now(properties.timezone()).minusDays(1);
        transactions.executeWithoutResult(tx -> {
            // 여러 서버가 같은 날을 동시에 정리해도 한 번만 실행한다.
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(724091)", Boolean.class))) return;
            var from = java.sql.Timestamp.from(day.atStartOfDay(properties.timezone()).toInstant());
            var to = java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(properties.timezone()).toInstant());
            var warehouses = jdbc.queryForList("select distinct warehouse_id from item_event "
                    + "where occurred_at >= ? and occurred_at < ?", java.util.UUID.class, from, to);
            // 원본이 없는 센터·날짜의 기존 집계를 지우지 않는다.
            for (var wh : warehouses) {
                jdbc.update("delete from item_event_daily where warehouse_id = ? and day = ?", wh, day);
                jdbc.update("insert into item_event_daily (warehouse_id, day, product_id, box_spec_id, status, "
                        + "failure_reason, source, shipments, fill_rate_sum) "
                        + "select warehouse_id, ?, product_id, box_spec_id, status, failure_reason, "
                        + "case when event_id like 'live-%' then 'live' else 'hist' end, count(*), sum(fill_rate_pct) "
                        + "from item_event where warehouse_id = ? and occurred_at >= ? and occurred_at < ? "
                        + "group by warehouse_id, product_id, box_spec_id, status, failure_reason, "
                        + "case when event_id like 'live-%' then 'live' else 'hist' end", day, wh, from, to);
            }
            log.info("출고 일별 집계 완료: day={}, warehouses={}", day, warehouses.size());
        });
    }
}
