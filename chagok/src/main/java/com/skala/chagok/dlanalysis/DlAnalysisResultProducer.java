package com.skala.chagok.dlanalysis;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 로컬 표본 재생도 Worker와 같은 결과 토픽을 사용하며 브로커 확인 전에는 접수 행을 만들지 않는다.
 *
 * <p>요청 프로듀서와 달리 전용 예외 대신 IllegalStateException을 던진다. 요청 발행 실패는 HTTP 503으로 나가는 계약이지만,
 * 이 발행은 HTTP 밖의 라이브 입력기만 부르고 실패하면 다음 틱에 같은 표본을 다시 시도한다.
 */
@Component
@KafkaIntegrationEnabled
public class DlAnalysisResultProducer {
    private final KafkaTemplate<String, DlAnalysisResult> kafka;
    private final String topic;
    private final Duration timeout;

    DlAnalysisResultProducer(KafkaTemplate<String, DlAnalysisResult> kafka, DlAnalysisResultTopicProperties results) {
        this.kafka = kafka;
        this.topic = results.topic();
        this.timeout = results.publishTimeout();
    }

    public void publish(DlAnalysisResult result) {
        try {
            kafka.send(topic, result.eventId(), result).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("라이브 결과 발행 중단", e);
        } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException e) {
            throw new IllegalStateException("라이브 결과 발행 확인 실패", e);
        }
    }
}
