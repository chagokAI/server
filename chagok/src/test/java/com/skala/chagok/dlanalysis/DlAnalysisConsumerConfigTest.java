package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 데드레터 발행마저 실패했을 때 결과를 잃지 않는지 확인한다.
 *
 * <p>이 보장은 프레임워크 기본값(발행 실패 시 예외, 복구 실패 시 같은 위치로 되돌림)에 기대고 있다. 설정 한 줄로 꺼질 수
 * 있는 값이라, 꺼지면 데드레터가 쓰이지 않는 순간 결과가 조용히 커밋되어 사라진다. 그 경로를 테스트로 고정한다.
 */
class DlAnalysisConsumerConfigTest {

    private static final String RESULT_TOPIC = "chagok.dl.analysis-result.v1";

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resultStaysUncommittedWhenTheDeadLetterCannotBeWritten() {
        KafkaOperations kafka = mock(KafkaOperations.class);
        ProducerFactory producerFactory = mock(ProducerFactory.class);
        when(kafka.getProducerFactory()).thenReturn(producerFactory);
        when(producerFactory.getConfigurationProperties()).thenReturn(Map.of());
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("dead letter topic unavailable")));
        var handler = new DlAnalysisConsumerConfig().dlAnalysisErrorHandler(mock(DlAnalysisResultRecorder.class),
                mock(DlMeasurementLog.class), mock(TransactionTemplate.class), mock(DlAnalysisItemEventRepository.class),
                mock(ApplicationEventPublisher.class), kafka,
                new DlAnalysisResultTopicProperties(RESULT_TOPIC, 3, 1, Duration.ofDays(7), Duration.ofSeconds(10)));
        ConsumerRecord<String, String> record = new ConsumerRecord<>(RESULT_TOPIC, 1, 42L, "evt-1", "{}");
        Consumer<?, ?> consumer = mock(Consumer.class);

        // 재시도하지 않는 예외라 곧바로 데드레터로 보내려 하고, 그 발행이 실패한다
        assertThatThrownBy(() -> handler.handleRemaining(
                new ListenerExecutionFailedException("listener failed", new IllegalStateException("bug")),
                List.of(record), consumer, mock(MessageListenerContainer.class)));

        verify(consumer).seek(new TopicPartition(RESULT_TOPIC, 1), 42L);
    }
}
