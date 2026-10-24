package com.skala.chagok.dlanalysis;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * DL 분석 결과 처리가 실패했을 때 예외마다 재시도 간격을 고른다.
 *
 * <ul>
 *   <li>ORPHAN_MESSAGE — 저장보다 결과가 먼저 도착했을 수 있어 0.5초 간격 5회</li>
 *   <li>DB 연결 장애 — 5초 간격으로 계속. 결과를 버리면 작업이 PENDING에 남으므로 DB가 돌아올 때까지 기다린다</li>
 *   <li>그 밖의 예외 — 재시도하지 않는다. 소진 후 처리는 {@link DlAnalysisConsumerConfig}가 정한다</li>
 * </ul>
 *
 * <p>DB 연결 장애의 범위는 실측으로 넓혔다. DB를 멈춘 순간 처리 중이던 결과는 「쿼리 실패 → 롤백 시도 → 연결이 이미 닫힘」으로
 * 끝나고, 밖으로 나오는 예외는 JpaSystemException 안의 Hibernate TransactionException과 SQLState 없는 SQLException이었다.
 * Spring의 장애 예외 3종만 보던 때는 이것을 일반 오류로 보고 결과를 버렸다. 그래서 JDBC 수준의 연결 신호를 함께 본다.
 * 반대로 범위를 너무 넓히면 프로그래밍 오류가 파티션을 영원히 멈추므로, 트랜잭션 예외는 SQLException이 원인일 때만 포함한다.
 */
final class DlAnalysisRetryPolicy {

    static final FixedBackOff ORPHAN_BACK_OFF = new FixedBackOff(500L, 5);
    static final FixedBackOff DATABASE_BACK_OFF = new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS);
    static final FixedBackOff NO_RETRY = new FixedBackOff(0L, 0L);

    private DlAnalysisRetryPolicy() {
    }

    static BackOff backOffFor(Exception exception) {
        if (isDatabaseUnavailable(exception)) {
            return DATABASE_BACK_OFF;
        }
        if (reasonOf(exception) == DlAnalysisFailureReason.ORPHAN_MESSAGE) {
            return ORPHAN_BACK_OFF;
        }
        return NO_RETRY;
    }

    static DlAnalysisFailureReason reasonOf(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DlAnalysisException analysisException) {
                return analysisException.reason();
            }
        }
        return null;
    }

    static boolean isDatabaseUnavailable(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException
                    || isConnectionState(cause)
                    || isFailedCompletion(cause)) {
                return true;
            }
        }
        return false;
    }

    // SQLState 08은 연결 예외, 57P0은 PostgreSQL 서버 종료(관리자 종료·비정상 종료·기동 중)다
    private static boolean isConnectionState(Throwable cause) {
        if (!(cause instanceof SQLException sqlException) || sqlException.getSQLState() == null) {
            return false;
        }
        String state = sqlException.getSQLState();
        return state.startsWith("08") || state.startsWith("57P0");
    }

    // 커밋·롤백이 연결 오류로 실패했다면 처리 결과는 DB에 남지 않았으므로 다시 해도 안전하다. 닫힌 연결에서 Hikari가
    // 던지는 예외는 SQLState가 없다. 커밋이 데이터 오류(제약 위반 23505·직렬화 실패 40001 등)로 실패한 경우는 기다려도
    // 같으므로 넣지 않는다. 넣으면 같은 결과를 영원히 재시도해 파티션이 멈춘다.
    private static boolean isFailedCompletion(Throwable cause) {
        return cause instanceof org.hibernate.TransactionException
                && cause.getCause() instanceof SQLException sqlException
                && (sqlException.getSQLState() == null || isConnectionState(sqlException));
    }
}
