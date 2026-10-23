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

    @Test void publicationWaitsForTheInitialSnapshot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(reader.snapshot(warehouse)).thenAnswer(invocation -> {
            entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return snapshot();
        });
        var hub = new LiveStreamHub(reader, properties());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var subscribing = pool.submit(() -> hub.subscribe(warehouse));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var publishing = pool.submit(() -> hub.publish(warehouse, "packed", "result"));
            assertThatThrownBy(() -> publishing.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            subscribing.get(5,TimeUnit.SECONDS);
            publishing.get(5,TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
