package com.skala.chagok.live;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 단일 서버의 센터별 구독을 관리한다. 재연결 snapshot이 현재고를 복구하고 놓친 애니메이션은 재전송하지 않는다.
 * 발행은 커밋 직후 Kafka 소비 스레드에서 불리므로 브라우저 쓰기를 기다리지 않는다. 센터마다 전송 전용 스레드 하나가
 * 순서대로 보내므로, 느린 연결은 그 센터의 화면 전송만 늦추고 DL 결과 처리는 멈추지 않는다.
 */
@Slf4j
@Component
public class LiveStreamHub {
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final String SESSION_KEY = LiveStreamHub.class.getName() + ".subscriptions";
    private final Map<UUID, Set<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final LiveStockReader reader;
    private final LiveProperties properties;

    private final java.util.function.Supplier<SseEmitter> emitters;
    private final Map<UUID, ExecutorService> lanes = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    LiveStreamHub(LiveStockReader reader, LiveProperties properties) {
        this(reader, properties, () -> new SseEmitter(STREAM_TIMEOUT_MS));
    }

    LiveStreamHub(LiveStockReader reader, LiveProperties properties, java.util.function.Supplier<SseEmitter> emitters) {
        this.reader = reader;
        this.properties = properties;
        this.emitters = emitters;
    }

    public SseEmitter subscribe(UUID warehouseId) { return subscribe(warehouseId, null); }

    /** 슬롯 예약·초기 snapshot·구독 공개를 같은 센터 잠금 안에서 처리해 경합과 초기화 실패의 누수를 막는다. */
    public SseEmitter subscribe(UUID warehouseId, HttpSession session) {
        var group = subscribers.computeIfAbsent(warehouseId, id -> new CopyOnWriteArraySet<>());
        synchronized (group) {
            if (group.size() >= properties.maxSubscribersPerWarehouse())
                throw BusinessException.withDetails(ErrorCode.TEMPORARILY_UNAVAILABLE,
                        "실시간 재고를 보는 화면이 너무 많습니다. 잠시 후 다시 연결하세요.",
                        Map.of("retryAfterSeconds", properties.retryAfterSeconds()));
            var emitter = emitters.get();
            var subscription = new Subscription(group, emitter, session == null ? null : lease(session));
            emitter.onCompletion(subscription::release);
            emitter.onTimeout(subscription::close);
            emitter.onError(e -> subscription.close());
            group.add(subscription);
            try {
                if (subscription.session != null) subscription.session.attach(subscription);
                if (!send(subscription, SseEmitter.event().name("snapshot").data(reader.snapshot(warehouseId).withReason("connect"))))
                    throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE);
                return emitter;
            } catch (RuntimeException e) {
                subscription.close();
                throw e;
            }
        }
    }

    public boolean hasSubscribers(UUID warehouseId) {
        var group = subscribers.get(warehouseId);
        return group != null && !group.isEmpty();
    }

    /** 전송은 센터 전송 스레드에서 한다. 같은 센터 잠금을 잡으므로 진행 중인 초기 snapshot 뒤에 도착한다. */
    public void publish(UUID warehouseId, String name, Object data) {
        var group = subscribers.get(warehouseId);
        if (group == null || group.isEmpty()) return;
        dispatch(warehouseId, () -> {
            synchronized (group) {
                for (var subscription : group) send(subscription, SseEmitter.event().name(name).data(data));
            }
        });
    }

    /**
     * 조회 실패로 오래된 값에 머무는 연결을 닫아, 확정된 업무 재실행 없이 다음 snapshot으로 복구한다.
     * 구독 해제는 즉시 하고, 응답 종료는 전송 스레드에 맡겨 호출한 Kafka 스레드를 붙잡지 않는다.
     */
    public void disconnect(UUID warehouseId) {
        var group = subscribers.get(warehouseId);
        if (group == null) return;
        List<Subscription> closing = List.copyOf(group);
        closing.forEach(Subscription::release);
        dispatch(warehouseId, () -> closing.forEach(subscription -> subscription.emitter.complete()));
    }

    @Scheduled(fixedRateString = "${chagok.live.heartbeat-ms:15000}")
    void heartbeat() {
        subscribers.forEach((warehouseId, group) -> dispatch(warehouseId, () -> {
            synchronized (group) {
                group.forEach(subscription -> send(subscription, SseEmitter.event().comment("keep-alive")));
            }
        }));
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        lanes.values().forEach(ExecutorService::shutdownNow);
    }

    private void dispatch(UUID warehouseId, Runnable task) {
        try {
            lanes.computeIfAbsent(warehouseId, id -> Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("live-sse-" + id).factory())).execute(task);
        } catch (RejectedExecutionException e) {
            // 종료 중에는 화면 전송을 버린다. 재접속 snapshot이 현재고를 다시 보낸다.
            log.debug("라이브 전송 생략(종료 중): warehouseId={}", warehouseId);
        }
    }

    private static boolean send(Subscription subscription, SseEmitter.SseEventBuilder event) {
        try {
            subscription.emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            subscription.close();
            log.debug("라이브 구독 정리(전송 실패): {}", e.getMessage());
            return false;
        }
    }

    private static SessionLease lease(HttpSession session) {
        try {
            synchronized (session) {
                var value = session.getAttribute(SESSION_KEY);
                if (value instanceof SessionLease existing) return existing;
                var created = new SessionLease();
                session.setAttribute(SESSION_KEY, created);
                return created;
            }
        } catch (IllegalStateException e) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }

    private static final class Subscription {
        final Set<Subscription> group;
        final SseEmitter emitter;
        final SessionLease session;
        Subscription(Set<Subscription> group, SseEmitter emitter, SessionLease session) {
            this.group = group; this.emitter = emitter; this.session = session;
        }
        void release() { group.remove(this); if (session != null) session.subscriptions.remove(this); }
        void close() { release(); emitter.complete(); }
    }

    /** 세션 무효화·만료가 다른 탭의 연결까지 즉시 회수한다. 화면 이탈에만 권한 회수를 의존하지 않는다. */
    private static final class SessionLease implements HttpSessionBindingListener {
        private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
        private boolean active = true;
        synchronized void attach(Subscription subscription) {
            if (!active) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            subscriptions.add(subscription);
        }
        @Override public void valueUnbound(HttpSessionBindingEvent event) {
            List<Subscription> closing;
            synchronized (this) { active = false; closing = List.copyOf(subscriptions); subscriptions.clear(); }
            closing.forEach(Subscription::close);
        }
    }
}
