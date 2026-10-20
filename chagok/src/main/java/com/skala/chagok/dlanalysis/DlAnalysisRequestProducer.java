package com.skala.chagok.dlanalysis;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * DL 분석 요청을 요청 토픽에 발행한다.
 *
 * <p>키는 jobId다. 같은 작업의 메시지가 같은 파티션에 쌓여 순서가 유지되고, 다른 작업 사이의 순서는 보장하지 않는다.
 *
 * <p>작업 상태는 여기서 바꾸지 않는다. 발행 성공이 분석 접수 완료를 뜻하지 않고, 발행 실패 시 작업을
 * PENDING으로 두고 재전송하는 일도 작업 서비스의 몫이므로 발행 결과를 그대로 호출자에게 돌려준다.
 */
@Component
public class DlAnalysisRequestProducer {

    private final KafkaTemplate<String, DlAnalysisRequest> kafkaTemplate;
    private final String topic;

    DlAnalysisRequestProducer(
            KafkaTemplate<String, DlAnalysisRequest> kafkaTemplate,
            DlAnalysisRequestTopicProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = properties.topic();
    }

    public CompletableFuture<SendResult<String, DlAnalysisRequest>> send(DlAnalysisRequest request) {
        return kafkaTemplate.send(topic, request.jobId(), request);
    }
}
