package com.skala.chagok.live;

import com.skala.chagok.domain.event.LiveEventIds;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/** 스케줄을 명시적으로 켠 DB의 라이브 변경분만 센터별로 정리한다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "chagok.live.reset.enabled", havingValue = "true")
public class LiveResetScheduler {
    private final JdbcTemplate jdbc;
    private final LiveResetService reset;
    // 연속 재생 중에는 쌓인 라이브 출고가 실제 이력이므로 초기화하지 않는다(설정을 같이 켜 둬도 안전하게)
    @Value("${chagok.live.continuous.enabled:false}") private boolean continuous;

    LiveResetScheduler(JdbcTemplate jdbc, LiveResetService reset, @Value("${spring.datasource.url}") String url,
            @Value("${chagok.live.allow-remote-database:false}") boolean allowRemote) {
        LiveDatabaseGuard.requireAllowed(url, allowRemote, "live reset scheduler");
        this.jdbc=jdbc; this.reset=reset;
    }

    @Scheduled(cron = "${chagok.live.reset.cron:0 */30 * * * *}", zone = "${chagok.timezone:Asia/Seoul}")
    public void tick() {
        if (continuous) return;
        var warehouses = jdbc.query("select distinct warehouse_id from item_event where event_id like ?",
                (rs, i) -> rs.getObject(1, UUID.class), LiveEventIds.PREFIX + "%");
        for (var id : warehouses) {
            try { reset.reset(id); }
            catch (RuntimeException e) { log.warn("라이브 초기화 실패: warehouseId={}", id, e); }
        }
    }
}
