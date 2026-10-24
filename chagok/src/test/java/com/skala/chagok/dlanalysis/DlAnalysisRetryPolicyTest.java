package com.skala.chagok.dlanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import org.hibernate.TransactionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * 결과 컨슈머가 예외마다 어떤 재시도 간격을 고르는지 확인한다.
 *
 * <p>DB 연결 장애로 판정한 예외는 결과를 버리지 않고 DB가 돌아올 때까지 기다린다. 2026-10-24 실험에서 DB를 멈춘 순간
 * 처리 중이던 결과가 「롤백 실패 → 연결 닫힘」 예외로 끝났고, 이 예외가 장애로 분류되지 않아 결과 1건이 버려졌다.
 * 반대로 프로그래밍 오류까지 DB 장애로 보면 같은 파티션이 영원히 멈추므로 반례도 함께 고정한다.
 */
class DlAnalysisRetryPolicyTest {

    @Test
    void rollbackFailureOnAClosedConnectionWaitsForTheDatabase() {
        // 실험에서 관찰한 사슬 그대로다. Hikari의 닫힌 연결 예외에는 SQLState가 없다
        var observed = listenerFailure(new JpaSystemException(
                new TransactionException("Unable to rollback against JDBC Connection",
                        new SQLException("Connection is closed"))));

        assertThat(DlAnalysisRetryPolicy.backOffFor(observed)).isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
    }

    @Test
    void connectionAndShutdownSqlStatesWaitForTheDatabase() {
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new JpaSystemException(
                new RuntimeException(new SQLException("terminating connection due to administrator command", "57P01"))))))
                .isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new JpaSystemException(
                new RuntimeException(new SQLException("An I/O error occurred while sending to the backend", "08006"))))))
                .isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
    }

    @Test
    void transientAndRecoverableJdbcFailuresWaitForTheDatabase() {
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new JpaSystemException(
                new RuntimeException(new SQLTransientConnectionException("pool timeout"))))))
                .isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new JpaSystemException(
                new RuntimeException(new SQLRecoverableException("socket closed"))))))
                .isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
    }

    @Test
    void springDatabaseFailuresKeepWaitingAsBefore() {
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new QueryTimeoutException("slow"))))
                .isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new DataAccessResourceFailureException("down"))))
                .isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new CannotCreateTransactionException("no tx"))))
                .isSameAs(DlAnalysisRetryPolicy.DATABASE_BACK_OFF);
    }

    @Test
    void missingRowIsRetriedBrieflyAsAnOrphan() {
        var orphan = listenerFailure(new DlAnalysisException(DlAnalysisFailureReason.ORPHAN_MESSAGE, "row missing"));

        assertThat(DlAnalysisRetryPolicy.backOffFor(orphan)).isSameAs(DlAnalysisRetryPolicy.ORPHAN_BACK_OFF);
    }

    @Test
    void programmingAndDataErrorsAreNotRetried() {
        // SQLException이 원인이 아닌 트랜잭션 예외는 연결 문제가 아니라 사용 오류다. 기다려도 풀리지 않는다
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new TransactionException("Transaction already active"))))
                .isSameAs(DlAnalysisRetryPolicy.NO_RETRY);
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new DataIntegrityViolationException("duplicate"))))
                .isSameAs(DlAnalysisRetryPolicy.NO_RETRY);
        // 커밋이 데이터 오류로 실패한 것(지연 제약 위반·직렬화 실패)은 연결 문제가 아니다. 영원히 재시도하면 파티션이 멈춘다
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new JpaSystemException(
                new TransactionException("Unable to commit against JDBC Connection",
                        new SQLException("duplicate key value violates unique constraint", "23505"))))))
                .isSameAs(DlAnalysisRetryPolicy.NO_RETRY);
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new JpaSystemException(
                new RuntimeException(new SQLException("syntax error", "42601"))))))
                .isSameAs(DlAnalysisRetryPolicy.NO_RETRY);
        assertThat(DlAnalysisRetryPolicy.backOffFor(listenerFailure(new IllegalStateException("bug"))))
                .isSameAs(DlAnalysisRetryPolicy.NO_RETRY);
    }

    private static Exception listenerFailure(Throwable cause) {
        return new ListenerExecutionFailedException("listener failed", cause);
    }
}
