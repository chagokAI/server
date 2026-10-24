package com.skala.chagok.live;

import com.skala.chagok.dlanalysis.DlAnalysisSettledEvent;
import com.skala.chagok.domain.inventory.service.StockChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 재고 반영이 취소될 수 있는 동안에는 보내지 않고, 커밋 후 확정된 상태만 읽어 전달한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveOutcomeRelay {
    private final LiveStockReader reader;
    private final LiveStreamHub hub;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void settled(DlAnalysisSettledEvent event) {
        if (!hub.hasSubscribers(event.warehouseId())) return;
        try {
            var item = reader.item(event.eventId());
            if (item == null) return;
            String name = switch (item.status()) {
                case "SUCCEEDED" -> "packed";
                case "WAITING" -> "waiting";
                case "FAILED" -> "failed";
                default -> null;
            };
            if (name != null) hub.publish(event.warehouseId(), name, item);
        } catch (RuntimeException e) {
            // 커밋된 포장을 스트림 장애 때문에 재처리하지 않는다. 재연결 snapshot으로 현재고를 복구한다.
            log.warn("라이브 결과 전송 실패: eventId={}, warehouseId={}", event.eventId(), event.warehouseId(), e);
            hub.disconnect(event.warehouseId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void stockChanged(StockChangedEvent event) {
        if (!hub.hasSubscribers(event.warehouseId())) return;
        try {
            hub.publish(event.warehouseId(), "snapshot",
                    reader.snapshot(event.warehouseId()).withReason(event.liveReset() ? "reset" : "stock"));
        } catch (RuntimeException e) {
            log.warn("라이브 재고 전송 실패: warehouseId={}", event.warehouseId(), e);
            hub.disconnect(event.warehouseId());
        }
    }
}
