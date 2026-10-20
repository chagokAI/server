package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * 발행 확인을 받지 못한 경우가 모두 같은 예외로 호출자에게 전달되는지 확인한다.
 * 작업 서비스는 이 예외 하나만 보고 「아무것도 저장하지 않고 503」으로 처리한다.
 */
class DlAnalysisRequestProducerFailureTest {

    private static final DlAnalysisRequest REQUEST = DlAnalysisRequest.of(
            "evt-1", "job-1", "wh-1", "sh-1", "captures/job-1/cam1.jpg", Instant.parse("2026-10-20T06:00:00Z"));

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, DlAnalysisRequest> kafkaTemplate = mock(KafkaTemplate.class);

    private final DlAnalysisRequestProducer producer = new DlAnalysisRequestProducer(
            kafkaTemplate,
            new DlAnalysisRequestTopicProperties("chagok.dl.analysis-request.v1", 3, 1, Duration.ofMillis(200)));

    @Test
    void wrapsBrokerFailureReportedThroughFuture() {
        when(kafkaTemplate.send(anyString(), anyString(), any(DlAnalysisRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker unavailable")));

        assertThatThrownBy(() -> producer.publish(REQUEST))
                .isInstanceOf(DlAnalysisRequestPublishException.class)
                .hasMessageContaining("job-1");
    }

    @Test
    void wrapsFailureThrownBeforeFutureIsReturned() {
        when(kafkaTemplate.send(anyString(), anyString(), any(DlAnalysisRequest.class)))
                .thenThrow(new TimeoutException("metadata not available"));

        assertThatThrownBy(() -> producer.publish(REQUEST))
                .isInstanceOf(DlAnalysisRequestPublishException.class);
    }

    @Test
    void givesUpWhenConfirmationDoesNotArriveWithinPublishTimeout() {
        when(kafkaTemplate.send(anyString(), anyString(), any(DlAnalysisRequest.class)))
                .thenReturn(new CompletableFuture<SendResult<String, DlAnalysisRequest>>());

        assertThatThrownBy(() -> producer.publish(REQUEST))
                .isInstanceOf(DlAnalysisRequestPublishException.class)
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }
}
