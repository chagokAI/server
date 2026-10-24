package com.skala.chagok.dlanalysis;

import com.skala.chagok.domain.event.LiveEventIds;
import com.skala.chagok.domain.event.entity.ItemEvent;
import com.skala.chagok.domain.event.entity.ItemEventId;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import com.skala.chagok.live.LiveDatabaseGuard;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 이미지 추론을 재실행하지 않고 측정 표본을 신규 물류 입력으로 흘리는 시연기다. */
@Slf4j
@Component
@KafkaIntegrationEnabled
@ConditionalOnProperty(name = "chagok.live.feeder.enabled", havingValue = "true")
public class DlAnalysisReplayFeeder {
    private final DlAnalysisReplaySource source;
    private final DlAnalysisResultProducer producer;
    private final DlAnalysisItemEventRepository events;
    private final EntityManager entityManager;
    private final java.util.Map<UUID, DlAnalysisReplaySource.Cursor> cursors = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<UUID, String> running = new java.util.concurrent.ConcurrentHashMap<>();
    // 센터별 잠금. 느린 브로커 확인은 같은 센터의 정지만 기다리게 하고, 상태 조회·다른 센터 조작은 막지 않는다.
    private final java.util.Map<UUID, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    public DlAnalysisReplayFeeder(DlAnalysisReplaySource source, DlAnalysisResultProducer producer,
            DlAnalysisItemEventRepository events, EntityManager entityManager,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${chagok.live.allow-remote-database:false}") boolean allowRemote) {
        LiveDatabaseGuard.requireAllowed(jdbcUrl, allowRemote, "live feeder");
        this.source = source;
        this.producer = producer;
        this.events = events;
        this.entityManager = entityManager;
    }

    // 발행 확인·DB 저장 시간을 다음 간격에 더하지 않는다. 한 실행이 길면 같은 작업은 겹치지 않고 늦어진다.
    @Scheduled(fixedRateString = "${chagok.live.feeder.interval-ms:167}")
    public void tick() {
        for (UUID warehouseId : List.copyOf(running.keySet())) {
            synchronized (lock(warehouseId)) {
                if (running.containsKey(warehouseId)) replay(warehouseId);
            }
        }
    }

    /** 기능을 켜도 기본은 정지다. 같은 센터의 투입과 같은 잠금으로 정지 응답 뒤 새 발행을 막는다. */
    public void setRunning(UUID warehouseId, boolean enabled, String ownerSession) {
        synchronized (lock(warehouseId)) {
            if (enabled) running.put(warehouseId, ownerSession);
            else running.remove(warehouseId);
        }
    }

    public boolean isRunning(UUID warehouseId) { return running.containsKey(warehouseId); }

    /** 이전 세션의 만료가 새 운영자의 실행을 중단하지 않도록 소유 세션을 대조한다. */
    public void stopOwnedBy(UUID warehouseId, String sessionId) {
        synchronized (lock(warehouseId)) {
            if (sessionId != null) running.remove(warehouseId, sessionId);
        }
    }

    private Object lock(UUID warehouseId) { return locks.computeIfAbsent(warehouseId, id -> new Object()); }

    private void replay(UUID warehouseId) {
        String id = LiveEventIds.PREFIX + UUID.randomUUID();
        try {
            var next = source.next(cursors.get(warehouseId), warehouseId);
            if (next.isEmpty()) return;
            var sample = next.get();
            var now = Instant.now();
            var result = new DlAnalysisResult(1, id, sample.warehouseId().toString(), "MEASURED", sample.status(),
                    sample.barcode(), sample.size(), null, null, sample.latencyMs(), now.toString());
            if (result.contractViolation() != null) throw new IllegalStateException(result.contractViolation());
            producer.publish(result);
            events.save(ItemEvent.builder().id(new ItemEventId(id, now))
                    .warehouse(entityManager.getReference(Warehouse.class, sample.warehouseId()))
                    .status(ItemEventStatus.PENDING.name()).imageRefs(List.of()).build());
            cursors.put(warehouseId, sample.cursor());
            log.info("라이브 입력 접수: eventId={}, warehouseId={}", id, sample.warehouseId());
        } catch (RuntimeException e) {
            log.warn("라이브 입력 실패(다음 틱 재시도): eventId={}", id, e);
        }
    }
}
