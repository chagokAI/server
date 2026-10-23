package com.skala.chagok.dlanalysis;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** 로컬 표본 재생도 Worker와 같은 결과 토픽을 사용하며 브로커 확인 전에는 접수 행을 만들지 않는다. */
@Component
@KafkaIntegrationEnabled
public class DlAnalysisResultProducer {
    private final KafkaTemplate<String, DlAnalysisResult> kafka;
    private final String topic;
    private final Duration timeout;

    DlAnalysisResultProducer(KafkaTemplate<String, DlAnalysisResult> kafka,
            DlAnalysisResultTopicProperties results, DlAnalysisRequestTopicProperties requests) {
        this.kafka = kafka;
        this.topic = results.topic();
        this.timeout = requests.publishTimeout();
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
