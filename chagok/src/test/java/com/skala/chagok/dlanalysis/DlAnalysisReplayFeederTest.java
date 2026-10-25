package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;

class DlAnalysisReplayFeederTest {
    @Test void enablingTheFeederDoesNotStartInputUntilPlayIsRequested() {
        var source = mock(DlAnalysisReplaySource.class);
        var producer = mock(DlAnalysisResultProducer.class);
        var events = mock(DlAnalysisItemEventRepository.class);
        var entities = mock(EntityManager.class);
        var feeder = new DlAnalysisReplayFeeder(source, producer, events, entities, "jdbc:postgresql://localhost/test", false);
        feeder.tick();
        verifyNoInteractions(source, producer, events, entities);
    }
    // 연속 재생: 표본이 있는 센터를 시작 때 켜고, 화면 세션이 끝나도 멈추지 않는다. 운영자가 멈추면 멈춘다.
    @Test void continuousModeStartsSampleWarehousesAndIgnoresSessionExpiry() {
        var source = mock(DlAnalysisReplaySource.class);
        var warehouse = UUID.randomUUID();
        when(source.warehousesWithSamples()).thenReturn(java.util.List.of(warehouse));
        var feeder = new DlAnalysisReplayFeeder(source, mock(DlAnalysisResultProducer.class), mock(DlAnalysisItemEventRepository.class),
                mock(EntityManager.class), "jdbc:postgresql://localhost/test", false);
        org.springframework.test.util.ReflectionTestUtils.setField(feeder, "continuous", true);
        feeder.startContinuous();
        assertThat(feeder.isRunning(warehouse)).isTrue();
        feeder.setRunning(warehouse, true, "operator-session");      // 화면에서 다시 켜도
        feeder.stopOwnedBy(warehouse, "operator-session");           // 그 세션이 끝나도 계속
        assertThat(feeder.isRunning(warehouse)).isTrue();
        feeder.setRunning(warehouse, false, "operator-session");     // 운영자가 멈추면 멈춘다
        assertThat(feeder.isRunning(warehouse)).isFalse();
    }

    @Test void continuousModeIsOffByDefault() {
        var source = mock(DlAnalysisReplaySource.class);
        var feeder = new DlAnalysisReplayFeeder(source, mock(DlAnalysisResultProducer.class), mock(DlAnalysisItemEventRepository.class),
                mock(EntityManager.class), "jdbc:postgresql://localhost/test", false);
        feeder.startContinuous();
        verifyNoInteractions(source);
    }

    // 발행 확인에 60ms가 걸려도 투입 주기에 더해지지 않아야 한다. 잘못된 fixedDelay를 잡는다.
    @Test void scheduledReplayKeepsRoughlySixItemsPerSecondDespiteBrokerLatency() throws Exception {
        var source = mock(DlAnalysisReplaySource.class);
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String, DlAnalysisResult> kafka = mock(org.springframework.kafka.core.KafkaTemplate.class);
        when(kafka.send(eq("results"), anyString(), any(DlAnalysisResult.class))).thenAnswer(call ->
                java.util.concurrent.CompletableFuture.supplyAsync(() -> null,
                        java.util.concurrent.CompletableFuture.delayedExecutor(60, java.util.concurrent.TimeUnit.MILLISECONDS)));
        var producer = new DlAnalysisResultProducer(kafka, new DlAnalysisResultTopicProperties("results",3,1,java.time.Duration.ofDays(7),java.time.Duration.ofSeconds(1)));
        var events = mock(DlAnalysisItemEventRepository.class);
        var times = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var accepted = new java.util.concurrent.CountDownLatch(12);
        when(events.save(any())).thenAnswer(call -> {
            times.add(System.nanoTime());
            accepted.countDown();
            return call.getArgument(0);
        });
        var sample = new DlAnalysisReplaySource.Sample(
                new DlAnalysisReplaySource.Cursor(Instant.now(), (short) 1, "original"), UUID.randomUUID(),
                "known", "sample-code", new DlAnalysisResult.Size("measured", 20., 10., 5., 1000.), 12L);
        when(source.next(any(), eq(sample.warehouseId()))).thenReturn(Optional.of(sample));
        when(source.next(null, sample.warehouseId())).thenReturn(Optional.of(sample));
        var feeder = new DlAnalysisReplayFeeder(source, producer, events, mock(EntityManager.class), "jdbc:postgresql://localhost/test", false);
        feeder.setRunning(sample.warehouseId(), true, "test-session");
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yaml"));
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "live-enabled", java.util.Map.of("chagok.live.feeder.enabled", "true")));
            context.getEnvironment().getPropertySources().addLast(
                    new org.springframework.core.env.PropertiesPropertySource("application", yaml.getObject()));
            context.registerBean("replay", DlAnalysisReplayFeeder.class, () -> feeder);
            context.register(Scheduling.class);
            context.refresh();
            assertThat(context.getBean(DlAnalysisReplayFeeder.class)).isSameAs(feeder);
            assertThat(accepted.await(3500, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("the configured replay should accept twelve items in about two seconds").isTrue();
        }
        double spanMillis = (times.get(11) - times.getFirst()) / 1_000_000.;
        assertThat(spanMillis).as("eleven intervals including 60ms broker ACK per item").isBetween(1600., 2200.);
    }

    // 느린 브로커 확인 중에도 상태 조회와 다른 센터의 시작은 기다리지 않는다. 같은 센터의 정지는 진행 중인 발행 뒤에 끝나야
    // 정지 응답 이후 새 발행이 없다는 계약이 유지된다.
    @Test void aSlowBrokerAckBlocksOnlyThePauseOfTheSameWarehouse() throws Exception {
        var source = mock(DlAnalysisReplaySource.class);
        var producer = mock(DlAnalysisResultProducer.class);
        var events = mock(DlAnalysisItemEventRepository.class);
        var sample = new DlAnalysisReplaySource.Sample(
                new DlAnalysisReplaySource.Cursor(Instant.now(), (short) 1, "original"), UUID.randomUUID(),
                "known", "sample-code", new DlAnalysisResult.Size("measured", 20., 10., 5., 1000.), 12L);
        var other = UUID.randomUUID();
        when(source.next(any(), eq(sample.warehouseId()))).thenReturn(Optional.of(sample));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var ack = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(ack.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); return null; })
                .when(producer).publish(any());
        var feeder = new DlAnalysisReplayFeeder(source, producer, events, mock(EntityManager.class), "jdbc:postgresql://localhost/test", false);
        feeder.setRunning(sample.warehouseId(), true, "operator");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            pool.submit(feeder::tick);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(pool.submit(() -> feeder.isRunning(sample.warehouseId())).get(200, java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
            pool.submit(() -> feeder.setRunning(other, true, "other")).get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            var pausing = pool.submit(() -> feeder.setRunning(sample.warehouseId(), false, "operator"));
            assertThatThrownBy(() -> pausing.get(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            ack.countDown();
            pausing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            feeder.tick();
            verify(producer, times(1)).publish(any());
        } finally { ack.countDown(); pool.shutdownNow(); }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class Scheduling {}

    @Test void brokerFailureStoresNothingAndKeepsTheSameSampleForTheNextTick() {
        var source = mock(DlAnalysisReplaySource.class);
        @SuppressWarnings("unchecked")
        org.springframework.kafka.core.KafkaTemplate<String, DlAnalysisResult> kafka = mock(org.springframework.kafka.core.KafkaTemplate.class);
        when(kafka.send(eq("results"), anyString(), any(DlAnalysisResult.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new org.apache.kafka.common.KafkaException("broker offline")));
        var producer = new DlAnalysisResultProducer(kafka, new DlAnalysisResultTopicProperties("results",3,1,java.time.Duration.ofDays(7),java.time.Duration.ofMillis(50)));
        var events = mock(DlAnalysisItemEventRepository.class);
        var entityManager = mock(EntityManager.class);
        var sample = new DlAnalysisReplaySource.Sample(
                new DlAnalysisReplaySource.Cursor(Instant.now(), (short) 1, "original"), UUID.randomUUID(),
                "known", "sample-code", new DlAnalysisResult.Size("measured", 20., 10., 5., 1000.), 12L);
        when(source.next(null, sample.warehouseId())).thenReturn(Optional.of(sample));
        var feeder = new DlAnalysisReplayFeeder(source, producer, events, entityManager, "jdbc:postgresql://localhost/test", false);

        feeder.setRunning(sample.warehouseId(), true, "test-session");
        assertThatCode(feeder::tick).doesNotThrowAnyException();
        feeder.tick();

        verify(source, times(2)).next(null, sample.warehouseId());
        verifyNoInteractions(events, entityManager);
    }
}
