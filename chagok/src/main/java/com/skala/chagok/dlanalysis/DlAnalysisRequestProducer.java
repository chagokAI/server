package com.skala.chagok.dlanalysis;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.KafkaException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * DL 분석 요청을 요청 토픽에 발행하고, 브로커가 기록했다는 확인까지 기다린다.
 *
 * <p>키는 jobId다. 같은 작업의 메시지가 같은 파티션에 쌓여 순서가 유지되고, 다른 작업 사이의 순서는 보장하지 않는다.
 *
 * <p>작업 서비스는 「발행 확인 → 작업 저장」 순서로 접수한다. outbox 없이 정합성을 맞추는 방식이라
 * 발행 확인 전에는 아무것도 저장하지 않고, 확인을 못 받으면 예외로 알려 요청을 실패시킨다.
 * 기다리는 동안 DB 연결을 붙잡지 않도록 이 메서드는 트랜잭션 밖에서 부른다.
 */
@Slf4j
@Component
public class DlAnalysisRequestProducer {

    private final KafkaTemplate<String, DlAnalysisRequest> kafkaTemplate;
    private final String topic;
    private final Duration publishTimeout;

    DlAnalysisRequestProducer(
            KafkaTemplate<String, DlAnalysisRequest> kafkaTemplate,
            DlAnalysisRequestTopicProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = properties.topic();
        this.publishTimeout = properties.publishTimeout();
    }

    /**
     * @throws DlAnalysisRequestPublishException 발행이 실패했거나 publishTimeout 안에 확인을 받지 못했을 때
     */
    public void publish(DlAnalysisRequest request) {
        try {
            kafkaTemplate.send(topic, request.jobId(), request)
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DlAnalysisRequestPublishException(request.jobId(), e);
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            throw new DlAnalysisRequestPublishException(request.jobId(), e);
        }
    }

    /**
     * 첫 발행이 토픽 정보를 받아 오느라 느려지지 않도록 기동 직후 한 번 미리 조회한다.
     * 브로커가 아직 없으면 기동을 막지 않고 경고만 남긴다. 그 경우 첫 발행이 정보를 다시 받아 온다.
     */
    @EventListener(ApplicationReadyEvent.class)
    void fetchTopicMetadata() {
        try {
            kafkaTemplate.partitionsFor(topic);
        } catch (KafkaException e) {
            log.warn("DL 분석 요청 토픽 정보를 미리 받지 못했다: topic={}, cause={}", topic, e.getMessage());
        }
    }
}
