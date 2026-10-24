package com.skala.chagok.live;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.skala.chagok.domain.inventory.service.StockChangedEvent;
import com.skala.chagok.dlanalysis.DlAnalysisSettledEvent;

class LiveStreamHubTest {
    private final UUID warehouse = UUID.randomUUID();
    private final LiveStockReader reader = mock(LiveStockReader.class);
    private LiveProperties properties() { return new LiveProperties(1, 10, 15000, new LiveProperties.Feeder(false,2000), new LiveProperties.Reset(false,"0 */30 * * * *")); }
    private LiveStockReader.Snapshot snapshot() { return new LiveStockReader.Snapshot(Instant.now(), List.of()); }

    @Test void initialSnapshotFailureReturnsTheSlotImmediately() {
        var hub = new LiveStreamHub(reader, properties());
        when(reader.snapshot(warehouse)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(snapshot());
        assertThatThrownBy(() -> hub.subscribe(warehouse)).isInstanceOf(IllegalStateException.class);
        assertThat(hub.hasSubscribers(warehouse)).isFalse();
        assertThatCode(() -> hub.subscribe(warehouse)).doesNotThrowAnyException();
    }

    @Test void snapshotReadFailureAfterCommitForcesReconnectInsteadOfLeavingStaleStock() {
        var hub = new LiveStreamHub(reader, properties());
        when(reader.snapshot(warehouse)).thenReturn(snapshot()).thenThrow(new IllegalStateException("database unavailable"));
        hub.subscribe(warehouse);
        new LiveOutcomeRelay(reader, hub).stockChanged(new StockChangedEvent(warehouse));
        assertThat(hub.hasSubscribers(warehouse)).isFalse();
    }

    @Test void outcomeReadFailureAfterCommitAlsoForcesReconnect() {
        var hub = new LiveStreamHub(reader, properties());
        when(reader.snapshot(warehouse)).thenReturn(snapshot());
        hub.subscribe(warehouse);
        when(reader.item("event")).thenThrow(new IllegalStateException("database unavailable"));
        new LiveOutcomeRelay(reader, hub).settled(new DlAnalysisSettledEvent("event", warehouse));
        assertThat(hub.hasSubscribers(warehouse)).isFalse();
    }

    @Test void concurrentAdmissionCannotOverbookTheLastSlot() throws Exception {
        var policy = mock(LiveProperties.class);
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        when(policy.maxSubscribersPerWarehouse()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) { checked.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); }
            return 1;
        });
        when(reader.snapshot(warehouse)).thenReturn(snapshot());
        var hub = new LiveStreamHub(reader, policy);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> hub.subscribe(warehouse));
            assertThat(checked.await(5,TimeUnit.SECONDS)).isTrue();
            var started = new CountDownLatch(1);
            var second = pool.submit(() -> { started.countDown(); return hub.subscribe(warehouse); });
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(com.skala.chagok.global.error.BusinessException.class);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    // 커밋 직후 발행은 Kafka 소비 스레드에서 불린다. 발행이 화면 전송을 기다리면 모든 센터의 결과 처리가 멈춘다.
    @Test void aPublicationDuringTheInitialSnapshotArrivesAfterItWithoutBlockingThePublisher() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(reader.snapshot(warehouse)).thenAnswer(invocation -> {
            entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return snapshot();
        });
        var emitter = new RecordingEmitter(Integer.MAX_VALUE);
        var hub = new LiveStreamHub(reader, properties(), () -> emitter);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var subscribing = pool.submit(() -> hub.subscribe(warehouse));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> hub.publish(warehouse, "packed", "result")).get(150, TimeUnit.MILLISECONDS);
            release.countDown();
            subscribing.get(5,TimeUnit.SECONDS);
            await(() -> emitter.names.size() == 2);
            assertThat(emitter.names).containsExactly("snapshot", "packed");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void aStalledBrowserDoesNotBlockLaterPublications() throws Exception {
        when(reader.snapshot(warehouse)).thenReturn(snapshot());
        var emitter = new RecordingEmitter(2);
        var hub = new LiveStreamHub(reader, properties(), () -> emitter);
        hub.subscribe(warehouse);
        var pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                hub.publish(warehouse, "packed", "first");
                hub.publish(warehouse, "packed", "second");
                hub.heartbeat();
            }).get(300, TimeUnit.MILLISECONDS);
            emitter.release.countDown();
            await(() -> emitter.names.size() == 3);
            assertThat(emitter.names).containsExactly("snapshot", "packed", "packed");
        } finally { emitter.release.countDown(); pool.shutdownNow(); }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met within 5s");
            Thread.sleep(10);
        }
    }

    /** 브라우저 쓰기를 대신한다. blockFrom번째 전송부터 release 전까지 응답하지 않는 느린 연결을 흉내 낸다. */
    private static final class RecordingEmitter extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        final List<String> names = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        private final int blockFrom;
        RecordingEmitter(int blockFrom) { this.blockFrom = blockFrom; }
        @Override public void send(SseEventBuilder builder) throws java.io.IOException {
            String text = builder.build().iterator().next().getData().toString();
            if (text.startsWith("event:")) names.add(text.substring(6, text.indexOf('\n')));
            if (names.size() >= blockFrom) {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
