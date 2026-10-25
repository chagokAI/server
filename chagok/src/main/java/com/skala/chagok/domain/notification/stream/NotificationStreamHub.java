package com.skala.chagok.domain.notification.stream;

import com.skala.chagok.global.error.BusinessException;
import com.skala.chagok.global.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 본인 알림만 전달한다. DB 커밋과 화면 전송을 분리하고 재접속 시 목록을 다시 조회한다. */
@Component
public class NotificationStreamHub {
    private static final String SESSION_KEY = NotificationStreamHub.class.getName();
    private final Map<UUID, Set<Subscription>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("notification-sse").factory());
    private final Supplier<SseEmitter> emitters;

    @Autowired
    public NotificationStreamHub() { this(() -> new SseEmitter(30 * 60 * 1000L)); }

    NotificationStreamHub(Supplier<SseEmitter> emitters) { this.emitters = emitters; }

    public SseEmitter subscribe(UUID userId, HttpSession session) {
        synchronized (subscribers) {
            var group = subscribers.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet());
            if (group.size() >= 10) throw new BusinessException(ErrorCode.TEMPORARILY_UNAVAILABLE);
            var subscription = new Subscription(userId, emitters.get(), session == null ? null : lease(session));
            var emitter = subscription.emitter;
            emitter.onCompletion(subscription::release);
            emitter.onTimeout(subscription::close);
            emitter.onError(error -> subscription.close());
            group.add(subscription);
            try {
                if (subscription.session != null) subscription.session.attach(subscription);
                // 구독 등록 후 조회해야 등록 직전의 알림도 목록 조회로 복구된다.
                send(subscription, SseEmitter.event().name("connected").data(Map.of("refresh", true)));
                return emitter;
            } catch (RuntimeException error) {
                subscription.close();
                throw error;
            }
        }
    }

    @TransactionalEventListener
    public void changed(NotificationChanged event) {
        dispatch(() -> {
            synchronized (subscribers) {
                var group = subscribers.get(event.recipientId());
                if (group == null) return;
                Object data = event.notification() == null ? Map.of("refresh", true)
                        : Map.of("notification", event.notification());
                for (var subscription : List.copyOf(group))
                    send(subscription, SseEmitter.event().name("changed").data(data));
            }
        });
    }

    @Scheduled(fixedDelay = 15000)
    void heartbeat() {
        dispatch(() -> {
            synchronized (subscribers) {
                var all = subscribers.values().stream().flatMap(Set::stream).toList();
                all.forEach(subscription -> send(subscription, SseEmitter.event().comment("keep-alive")));
            }
        });
    }

    private void dispatch(Runnable action) {
        try { sender.execute(action); } catch (RejectedExecutionException ignored) { /* 서버 종료 중 */ }
    }

    private void send(Subscription subscription, SseEmitter.SseEventBuilder event) {
        try { subscription.emitter.send(event); }
        catch (IOException | IllegalStateException error) { subscription.close(); }
    }

    private static Lease lease(HttpSession session) {
        try {
            synchronized (session) {
                if (session.getAttribute(SESSION_KEY) instanceof Lease existing) return existing;
                var lease = new Lease();
                session.setAttribute(SESSION_KEY, lease);
                return lease;
            }
        } catch (IllegalStateException error) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }

    private final class Subscription {
        final UUID userId;
        final SseEmitter emitter;
        final Lease session;
        Subscription(UUID userId, SseEmitter emitter, Lease session) {
            this.userId = userId; this.emitter = emitter; this.session = session;
        }
        void release() {
            synchronized (subscribers) {
                var group = subscribers.get(userId);
                if (group != null) {
                    group.remove(this);
                    if (group.isEmpty()) subscribers.remove(userId);
                }
            }
            if (session != null) session.subscriptions.remove(this);
        }
        void close() { release(); emitter.complete(); }
    }

    /** 로그아웃·세션 만료 시 다른 탭의 연결도 즉시 닫는다. */
    private static final class Lease implements HttpSessionBindingListener {
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

    @PreDestroy
    public void shutdown() {
        synchronized (subscribers) {
            subscribers.values().stream().flatMap(Set::stream).toList().forEach(Subscription::close);
        }
        sender.shutdownNow();
    }
}
