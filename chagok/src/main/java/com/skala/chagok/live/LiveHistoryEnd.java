package com.skala.chagok.live;

import com.skala.chagok.domain.event.LiveEventIds;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 센터의 기존(라이브가 아닌) 출고 이력이 끝난 시각. 연속 재생은 이 시각 이후에만 켠다.
 * 시연·이관 데이터가 미래 시각까지 들어 있을 때 그 위에 라이브 출고가 겹쳐 쌓이지 않게 한다. 그 전에는 기존 초기화 모드로 동작한다.
 */
@Component
@RequiredArgsConstructor
public class LiveHistoryEnd {
    private final JdbcTemplate jdbc;

    public Instant of(UUID warehouseId) {
        Timestamp end = jdbc.queryForObject("select max(occurred_at) from item_event where warehouse_id = ? and event_id not like ?",
                Timestamp.class, warehouseId, LiveEventIds.PREFIX + "%");
        return end == null ? null : end.toInstant();
    }

    /** 기존 이력이 없거나 이미 지난 센터 */
    public boolean passed(UUID warehouseId) {
        Instant end = of(warehouseId);
        return end == null || Instant.now().isAfter(end);
    }
}
