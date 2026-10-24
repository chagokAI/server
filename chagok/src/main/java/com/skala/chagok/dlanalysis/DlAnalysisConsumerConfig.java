package com.skala.chagok.dlanalysis;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;

import lombok.extern.slf4j.Slf4j;

/**
 * DL 분석 결과 컨슈머의 재시도와 소진 후 처리.
 *
 * <p>Spring Boot는 CommonErrorHandler 빈이 하나 있으면 기본 리스너 컨테이너에 연결한다. 지금은 리스너가
 * DL 분석 결과 하나뿐이라 이 처리기가 곧 전체 규칙이다. 리스너가 늘면 컨테이너 팩토리를 나눠야 한다.
 *
 * <p>재시도 간격은 {@link DlAnalysisRetryPolicy}가 고른다. 소진 후 처리:
 * <ul>
 *   <li>역직렬화 실패 — 키(eventId)가 있으면 FAILED(INVALID_RESULT)로 기록한다. 버리기만 하면 작업이 PENDING에 남는다.
 *       키도 없으면 대응할 작업이 없으므로 로그만 남긴다</li>
 *   <li>그 밖의 전부(고아 재시도 소진 포함) — 데드레터 토픽으로 보낸다</li>
 * </ul>
 * 예전에는 고아와 「그 밖의 예외」를 로그만 남기고 버렸다. 버린 결과의 작업은 PENDING에서 나올 길이 없었고, 실험에서
 * DB 종료 순간의 결과 1건이 그렇게 사라졌다. 데드레터 토픽에 남기면 원인을 고친 뒤 결과 토픽으로 다시 넣을 수 있다.
 * 데드레터 발행이 실패하면 복구기가 예외를 던지고, 처리기는 같은 메시지를 다시 처리 대상으로 돌려놓는다(프레임워크 기본).
 * DL 일시 장애의 재시도는 DL Worker가 맡는다. Worker가 재시도 끝에 FAILED 결과를 보내므로 여기서는 다시 시도하지 않는다.
 */
@Slf4j
@Configuration
@KafkaIntegrationEnabled
class DlAnalysisConsumerConfig {

    @Bean
    DefaultErrorHandler dlAnalysisErrorHandler(DlAnalysisResultRecorder recorder, DlMeasurementLog measurementLog,
            org.springframework.transaction.support.TransactionTemplate transactions,
            DlAnalysisItemEventRepository itemEvents, org.springframework.context.ApplicationEventPublisher events,
            KafkaOperations<?, ?> kafka, DlAnalysisResultTopicProperties resultTopic) {
        var deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(resultTopic.deadLetterTopic(), record.partition()));
        // spring-kafka의 FailedRecordTracker는 기본 백오프가 「재시도 없음」이면 백오프 함수를 보지 않고 곧바로 복구한다.
        // 그러면 저장보다 먼저 도착한 결과가 재시도 없이 고아로 넘어간다(통합 테스트에서 확인). 그래서 기본 백오프는
        // 재시도가 있는 값으로 두고, 재시도하지 않을 예외는 함수가 NO_RETRY를 돌려주게 했다.
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> recover(recorder, measurementLog, transactions, itemEvents, events, deadLetters,
                        record, exception),
                DlAnalysisRetryPolicy.ORPHAN_BACK_OFF);
        handler.setBackOffFunction((record, exception) -> DlAnalysisRetryPolicy.backOffFor(exception));
        return handler;
    }

    private static void recover(
            DlAnalysisResultRecorder recorder,
            DlMeasurementLog measurementLog,
            org.springframework.transaction.support.TransactionTemplate transactions,
            DlAnalysisItemEventRepository itemEvents,
            org.springframework.context.ApplicationEventPublisher events,
            DeadLetterPublishingRecoverer deadLetters,
            ConsumerRecord<?, ?> record,
            Exception exception) {
        String eventId = record.key() == null ? null : String.valueOf(record.key());
        if (isDeserializationFailure(exception)) {
            if (eventId == null) {
                log.error("DL 분석 결과 버림(읽을 수 없고 eventId도 없음): offset={}", record.offset(), exception);
                return;
            }
            // 역직렬화 실패는 onResult를 거치지 않는다. 실패 기록과 알림 예약도 한 트랜잭션에 넣어 확정 후만 전달한다.
            DlAnalysisOutcome outcome = transactions.execute(status -> {
                var settled = recorder.recordFailure(eventId, DlAnalysisFailureReason.INVALID_RESULT,
                        "결과 메시지를 읽을 수 없다: " + exception.getMessage());
                if (settled != null && settled.status() != null)
                    itemEvents.findSnapshot(eventId).ifPresent(snapshot ->
                            events.publishEvent(new DlAnalysisSettledEvent(eventId, snapshot.getWarehouseId())));
                return settled;
            });
            measurementLog.write(eventId, null, outcome);
            return;
        }
        deadLetters.accept(record, exception);
        var reason = DlAnalysisRetryPolicy.reasonOf(exception);
        log.warn("DL 분석 결과를 데드레터로 보냄: eventId={}, offset={}, reason={}", eventId, record.offset(),
                reason == null ? exception.toString() : reason);
    }

    private static boolean isDeserializationFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException) {
                return true;
            }
        }
        return false;
    }
}
