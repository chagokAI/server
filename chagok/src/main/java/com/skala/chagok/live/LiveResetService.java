package com.skala.chagok.live;

import com.skala.chagok.dlanalysis.DlAnalysisReplayFeeder;
import com.skala.chagok.domain.inventory.service.InventoryPackingService;
import com.skala.chagok.domain.inventory.service.StockChangedEvent;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.warehouse.repository.WarehouseRepository;
import com.skala.chagok.global.error.BusinessException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;

/** 전체 DB를 복원하지 않고 라이브 입력이 사용한 박스만 되돌려 일반 입고·실사·포장 이력을 보존한다. */
@Slf4j
@Service
public class LiveResetService {
    private final WarehouseRepository warehouses;
    private final BoxSpecRepository boxes;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final String jdbcUrl;
    private final boolean allowRemote;

    public LiveResetService(WarehouseRepository warehouses, BoxSpecRepository boxes,
            NamedParameterJdbcTemplate jdbc, ApplicationEventPublisher events,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${chagok.live.allow-remote-database:false}") boolean allowRemote) {
        this.warehouses=warehouses; this.boxes=boxes; this.jdbc=jdbc; this.events=events; this.jdbcUrl=jdbcUrl;
        this.allowRemote=allowRemote;
    }

    @Transactional
    public void reset(UUID warehouseId) {
        LiveDatabaseGuard.requireAllowed(jdbcUrl, allowRemote, "live reset");
        warehouses.lockById(warehouseId).orElseThrow(() -> BusinessException.notFound("센터"));
        var params = Map.of("warehouseId", warehouseId,
                "memo", InventoryPackingService.MOVEMENT_MEMO_PREFIX + DlAnalysisReplayFeeder.LIVE_EVENT_PREFIX + "%",
                "prefix", DlAnalysisReplayFeeder.LIVE_EVENT_PREFIX + "%");
        jdbc.query("""
                select box_spec_id,count(*) as used from inventory_movement
                where warehouse_id=:warehouseId and type='CONSUMPTION' and memo like :memo group by box_spec_id
                """, params, rs -> {
            var box = boxes.findByIdAndWarehouseId(rs.getObject("box_spec_id", UUID.class), warehouseId).orElseThrow();
            if (box.getOnHandQty() == null) throw new IllegalStateException("초기화할 현재고가 없습니다.");
            box.changeStock(Math.addExact(box.getOnHandQty(), Math.toIntExact(rs.getLong("used"))), Instant.now());
        });
        boxes.flush();
        int movements = jdbc.update("delete from inventory_movement where warehouse_id=:warehouseId and type='CONSUMPTION' and memo like :memo", params);
        // 결과를 기다리는 접수 건은 남긴다. 지우면 늦은 결과가 고아 재시도로 소비 파티션을 멈춘다. 확정 후 차감은 다음 초기화가 되돌린다.
        int items = jdbc.update("delete from item_event where warehouse_id=:warehouseId and event_id like :prefix and status<>'PENDING'", params);
        events.publishEvent(new StockChangedEvent(warehouseId));
        log.info("라이브 변경분 초기화: warehouseId={}, movements={}, events={}", warehouseId, movements, items);
    }
}
