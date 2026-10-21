package com.skala.chagok.dlanalysis;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import lombok.extern.slf4j.Slf4j;

/**
 * DL 분석 결과 컨슈머의 재시도와 소진 후 처리.
 *
 * <p>Spring Boot는 CommonErrorHandler 빈이 하나 있으면 기본 리스너 컨테이너에 연결한다. 지금은 리스너가
 * DL 분석 결과 하나뿐이라 이 처리기가 곧 전체 규칙이다. 리스너가 늘면 컨테이너 팩토리를 나눠야 한다.
 *
 * <p>재시도 규칙:
 * <ul>
 *   <li>ORPHAN_MESSAGE — 저장보다 결과가 먼저 도착했을 수 있어 0.5초 간격 5회. 소진하면 고아 메시지로 보고 버린다</li>
 *   <li>DB 연결 장애 — 5초 간격으로 계속. 결과를 버리면 작업이 PENDING에 영영 남으므로 DB가 돌아올 때까지 기다린다</li>
 *   <li>역직렬화 실패 — 재시도 없이, 키(eventId)가 있으면 FAILED(INVALID_RESULT)로 기록한다. 버리기만 하면 작업이 PENDING에 남는다</li>
 *   <li>그 밖의 예외 — 재시도 없이 로그를 남기고 버린다</li>
 * </ul>
 * DL 일시 장애의 재시도는 DL Worker가 맡는다. Worker가 재시도 끝에 FAILED 결과를 보내므로 여기서는 다시 시도하지 않는다.
 * 소진 후 FAILED 기록마저 DB 장애로 실패하면 처리기가 같은 메시지를 다시 처리 대상으로 돌려놓으므로 메시지를 잃지 않는다.
 */
@Slf4j
@Configuration
@KafkaIntegrationEnabled
class DlAnalysisConsumerConfig {

    private static final FixedBackOff ORPHAN_BACK_OFF = new FixedBackOff(500L, 5);
    private static final FixedBackOff DATABASE_BACK_OFF = new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS);
    private static final FixedBackOff NO_RETRY = new FixedBackOff(0L, 0L);

    @Bean
    DefaultErrorHandler dlAnalysisErrorHandler(DlAnalysisResultRecorder recorder, DlMeasurementLog measurementLog) {
        // spring-kafka의 FailedRecordTracker는 기본 백오프가 「재시도 없음」이면 백오프 함수를 보지 않고 곧바로 복구한다.
        // 그러면 저장보다 먼저 도착한 결과가 재시도 없이 고아로 버려진다(통합 테스트에서 확인). 그래서 기본 백오프는
        // 재시도가 있는 값으로 두고, 재시도하지 않을 예외는 함수가 NO_RETRY를 돌려주게 했다.
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> recover(recorder, measurementLog, record, exception), ORPHAN_BACK_OFF);
        handler.setBackOffFunction((record, exception) -> backOffFor(exception));
        return handler;
    }

    private static BackOff backOffFor(Exception exception) {
        if (isDatabaseUnavailable(exception)) {
            return DATABASE_BACK_OFF;
        }
        if (reasonOf(exception) == DlAnalysisFailureReason.ORPHAN_MESSAGE) {
            return ORPHAN_BACK_OFF;
        }
        return NO_RETRY;
    }

    private static void recover(
            DlAnalysisResultRecorder recorder,
            DlMeasurementLog measurementLog,
            ConsumerRecord<?, ?> record,
            Exception exception) {
        String eventId = record.key() == null ? null : String.valueOf(record.key());
        if (reasonOf(exception) == DlAnalysisFailureReason.ORPHAN_MESSAGE) {
            log.warn("DL 분석 결과 버림: eventId={}, reason={}", eventId, DlAnalysisFailureReason.ORPHAN_MESSAGE);
            return;
        }
        if (isDeserializationFailure(exception) && eventId != null) {
            DlAnalysisOutcome outcome = recorder.recordFailure(
                    eventId, DlAnalysisFailureReason.INVALID_RESULT, "결과 메시지를 읽을 수 없다: " + exception.getMessage());
            measurementLog.write(eventId, null, outcome);
            return;
        }
        log.error("DL 분석 결과 버림(처리 불가): eventId={}, offset={}", eventId, record.offset(), exception);
    }

    private static DlAnalysisFailureReason reasonOf(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DlAnalysisException analysisException) {
                return analysisException.reason();
            }
        }
        return null;
    }

    private static boolean isDeserializationFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDatabaseUnavailable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
