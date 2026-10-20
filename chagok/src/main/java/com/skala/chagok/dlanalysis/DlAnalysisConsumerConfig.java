package com.skala.chagok.dlanalysis;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import lombok.extern.slf4j.Slf4j;

/**
 * DL 분석 컨슈머의 재시도와 소진 후 처리.
 *
 * <p>Spring Boot는 CommonErrorHandler 빈이 하나 있으면 기본 리스너 컨테이너에 연결한다. 지금은 리스너가
 * DL 분석 하나뿐이라 이 처리기가 곧 전체 규칙이다. 리스너가 늘면 컨테이너 팩토리를 나눠야 한다.
 *
 * <p>재시도 규칙:
 * <ul>
 *   <li>ORPHAN_MESSAGE — 저장보다 메시지가 먼저 도착했을 수 있어 0.5초 간격 5회. 소진하면 고아 메시지로 보고 버린다</li>
 *   <li>DL_UNAVAILABLE — 2초 간격 3회. 소진하면 FAILED로 기록한다</li>
 *   <li>DB 연결 장애 — 5초 간격으로 계속. 메시지를 버리면 작업이 PENDING에 영영 남으므로 DB가 돌아올 때까지 기다린다</li>
 *   <li>그 밖의 예외(역직렬화 실패 포함) — 재시도 없이 로그를 남기고 버린다</li>
 * </ul>
 * 소진 후 FAILED 기록마저 DB 장애로 실패하면 처리기가 같은 메시지를 다시 처리 대상으로 돌려놓으므로 메시지를 잃지 않는다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DlMeasureClientProperties.class)
class DlAnalysisConsumerConfig {

    private static final FixedBackOff ORPHAN_BACK_OFF = new FixedBackOff(500L, 5);
    private static final FixedBackOff DL_BACK_OFF = new FixedBackOff(2000L, 3);
    private static final FixedBackOff DATABASE_BACK_OFF = new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS);
    private static final FixedBackOff NO_RETRY = new FixedBackOff(0L, 0L);

    @Bean
    DefaultErrorHandler dlAnalysisErrorHandler(DlAnalysisResultRecorder recorder) {
        // 처음에는 기본 백오프를 NO_RETRY로 두고 사유별 간격을 백오프 함수로만 정했다. 그런데 spring-kafka의
        // FailedRecordTracker는 기본 백오프가 「재시도 없음」이면 백오프 함수를 보지 않고 곧바로 복구한다.
        // 통합 테스트에서 저장보다 먼저 도착한 메시지가 재시도 없이 고아로 버려져 작업이 PENDING에 남는 것으로 드러났다.
        // 그래서 기본 백오프는 재시도가 있는 값으로 두고, 재시도하지 않을 예외는 함수가 NO_RETRY를 돌려주게 했다.
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> recover(recorder, record, exception), DL_BACK_OFF);
        handler.setBackOffFunction((record, exception) -> backOffFor(exception));
        return handler;
    }

    private static BackOff backOffFor(Exception exception) {
        if (isDatabaseUnavailable(exception)) {
            return DATABASE_BACK_OFF;
        }
        DlAnalysisFailureReason reason = reasonOf(exception);
        if (reason == DlAnalysisFailureReason.ORPHAN_MESSAGE) {
            return ORPHAN_BACK_OFF;
        }
        if (reason == DlAnalysisFailureReason.DL_UNAVAILABLE) {
            return DL_BACK_OFF;
        }
        return NO_RETRY;
    }

    private static void recover(DlAnalysisResultRecorder recorder, ConsumerRecord<?, ?> record, Exception exception) {
        String eventId = String.valueOf(record.key());
        DlAnalysisFailureReason reason = reasonOf(exception);
        if (reason == DlAnalysisFailureReason.DL_UNAVAILABLE) {
            recorder.recordFailure(eventId, reason, exception.getMessage());
            return;
        }
        if (reason == DlAnalysisFailureReason.ORPHAN_MESSAGE) {
            log.warn("DL 분석 요청 버림: eventId={}, reason={}", eventId, reason);
            return;
        }
        log.error("DL 분석 요청 버림(처리 불가): eventId={}, offset={}", eventId, record.offset(), exception);
    }

    private static DlAnalysisFailureReason reasonOf(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DlAnalysisException analysisException) {
                return analysisException.reason();
            }
        }
        return null;
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
