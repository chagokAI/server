package com.skala.chagok.live;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/** 단일 서버의 센터별 구독을 관리한다. 재연결 snapshot이 현재고를 복구하고 놓친 애니메이션은 재전송하지 않는다. */
@Slf4j
@Component
public class LiveStreamHub {
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final String SESSION_KEY = LiveStreamHub.class.getName() + ".subscriptions";
    private final Map<UUID, Set<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final LiveStockReader reader;
    private final LiveProperties properties;

    LiveStreamHub(LiveStockReader reader, LiveProperties properties) {
        this.reader = reader;
        this.properties = properties;
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
            var emitter = new SseEmitter(STREAM_TIMEOUT_MS);
            var subscription = new Subscription(group, emitter, session == null ? null : lease(session));
            emitter.onCompletion(subscription::release);
            emitter.onTimeout(subscription::close);
            emitter.onError(e -> subscription.close());
            group.add(subscription);
            try {
                if (subscription.session != null) subscription.session.attach(subscription);
                if (!send(subscription, SseEmitter.event().name("snapshot").data(reader.snapshot(warehouseId))))
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

    public void publish(UUID warehouseId, String name, Object data) {
        var group = subscribers.get(warehouseId);
        if (group == null) return;
        synchronized (group) {
            for (var subscription : group) send(subscription, SseEmitter.event().name(name).data(data));
        }
    }

    /** 조회 실패로 오래된 값에 머무는 연결을 닫아, 확정된 업무 재실행 없이 다음 snapshot으로 복구한다. */
    public void disconnect(UUID warehouseId) {
        var group = subscribers.get(warehouseId);
        if (group == null) return;
        synchronized (group) { group.forEach(Subscription::close); }
    }

    @Scheduled(fixedRateString = "${chagok.live.heartbeat-ms:15000}")
    void heartbeat() {
        subscribers.values().forEach(group -> {
            synchronized (group) {
                group.forEach(subscription -> send(subscription, SseEmitter.event().comment("keep-alive")));
            }
        });
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
