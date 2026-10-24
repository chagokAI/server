package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * 결과 토픽 발행은 결과 토픽 자신의 확인 대기 시간을 쓴다. 예전에는 요청 토픽 설정에서 빌려 와,
 * 요청 쪽 값을 바꾸면 라이브 입력기의 대기 시간까지 함께 바뀌었다.
 */
class DlAnalysisResultProducerTest {

    @Test
    void givesUpAfterTheResultTopicsOwnPublishTimeout() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, DlAnalysisResult> kafka = mock(KafkaTemplate.class);
        when(kafka.send(eq("results"), anyString(), any(DlAnalysisResult.class)))
                .thenReturn(new CompletableFuture<SendResult<String, DlAnalysisResult>>());
        var producer = new DlAnalysisResultProducer(kafka,
                new DlAnalysisResultTopicProperties("results", 3, 1, Duration.ofDays(7), Duration.ofMillis(50)));

        long started = System.nanoTime();
        assertThatThrownBy(() -> producer.publish(new DlAnalysisResult(1, "live-1", "w", "MEASURED", "known",
                "8809037045013", null, null, null, 1L, "2026-10-24T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    }
}
