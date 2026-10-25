package com.skala.chagok.domain.notification.stream;

import com.skala.chagok.domain.notification.dto.NotificationPageResponse;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class NotificationStreamHubTest {
    @Test void onlyCommittedChangesReachTheRecipientAndLogoutClosesAllSessionStreams() {
        var emitters = new ArrayList<RecordingEmitter>();
        try (var context = new AnnotationConfigApplicationContext()) {
            var hub = new NotificationStreamHub(() -> {
                var emitter = new RecordingEmitter(); emitters.add(emitter); return emitter;
            });
            context.registerBean(NotificationStreamHub.class, () -> hub);
            context.registerBean(org.springframework.transaction.event.TransactionalEventListenerFactory.class);
            context.refresh();
            var transaction = new TransactionTemplate(new TestTransactionManager());
            var user = UUID.randomUUID();
            var session = new MockHttpSession();
            hub.subscribe(user, session);
            hub.subscribe(UUID.randomUUID(), new MockHttpSession());
            var mine = emitters.get(0);
            var other = emitters.get(1);
            var item = new NotificationPageResponse.Item(UUID.randomUUID(), "AI", "새 알림", "내용", "/orders",
                    Instant.now(), null, true);
            transaction.executeWithoutResult(status -> {
                context.publishEvent(new NotificationChanged(user, item));
                assertThat(mine.events).hasSize(1); // connected만 전송됨
            });
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(mine.events).hasSize(2));
            assertThat(other.events).hasSize(1);
            assertThat(mine.events.get(1)).contains("event:changed");

            transaction.executeWithoutResult(status -> {
                context.publishEvent(new NotificationChanged(user, item));
                status.setRollbackOnly();
            });
            // 이어지는 커밋을 전송 장벽으로 사용하여 롤백분이 큐에 없는지 확인한다.
            transaction.executeWithoutResult(status -> context.publishEvent(new NotificationChanged(user, null)));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(mine.events).hasSize(3));
            assertThat(mine.events.get(2)).contains("refresh=true");

            hub.subscribe(user, session);
            session.invalidate();
            assertThat(mine.closed).isTrue();
            assertThat(emitters.get(2).closed).isTrue();
            assertThat(other.closed).isFalse();
            transaction.executeWithoutResult(status -> context.publishEvent(new NotificationChanged(user, item)));
            transaction.executeWithoutResult(status -> context.publishEvent(new NotificationChanged(
                    item.id(), null)));
            assertThat(mine.events).hasSize(3);
        }
    }

    private static class RecordingEmitter extends SseEmitter {
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        @Override public void send(SseEventBuilder event) {
            events.add(event.build().stream().map(part -> String.valueOf(part.getData())).reduce("", String::concat));
        }
        @Override public void complete() { closed = true; }
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
