package com.skala.chagok.domain.job.entity;

import com.skala.chagok.domain.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

/** 작업 실행기가 기록할 상태. 조회 요청 자체는 작업을 생성하거나 실행하지 않는다. */
@Entity
@Table(name = "analysis_job", indexes = @Index(name = "ix_job_warehouse_month", columnList = "warehouse_id,target_month,created_at"))
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AnalysisJob {
    public enum Type { PROCUREMENT_AND_COST }
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, PUBLISH_FAILED, TIMED_OUT }
    public enum Trigger { MANUAL, SCHEDULED, RETRY }

    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "warehouse_id", nullable = false) private Warehouse warehouse;
    @Column(name = "target_month", nullable = false, length = 7) private String targetMonth;
    @Enumerated(EnumType.STRING) @Column(name = "job_type", nullable = false, length = 40) private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "data_as_of") private Instant dataAsOf;
    @Column(name = "proposal_id") private UUID proposalId;
    @Column(name = "cost_forecast_id") private UUID costForecastId;
    @Column(name = "failure_code", length = 80) private String failureCode;
    @Column(name = "failure_message", length = 500) private String failureMessage;
    @Version private Integer version;
    @Enumerated(EnumType.STRING) @Column(name = "trigger_source", length = 20) private Trigger trigger;
    /** 활성 작업에만 값이 있다. DB 유니크 제약으로 같은 범위의 동시 실행도 막는다. */
    @Column(name = "active_key", unique = true, length = 100) private String activeKey;
    @Column(name = "retry_of_job_id") private UUID retryOfJobId;
    @Column(name = "retry_job_id") private UUID retryJobId;

    public static AnalysisJob pending(Warehouse warehouse, String month, Type type, Trigger trigger, Instant now) {
        return pending(warehouse, month, type, trigger, now, null);
    }

    public static AnalysisJob pending(Warehouse warehouse, String month, Type type, Trigger trigger, Instant now, UUID retryOf) {
        return AnalysisJob.builder().warehouse(warehouse).targetMonth(month).type(type).trigger(trigger)
                .status(Status.PENDING).createdAt(now).activeKey(key(warehouse.getId(), month, type)).retryOfJobId(retryOf).build();
    }

    public static String key(UUID warehouseId, String month, Type type) {
        return warehouseId + ":" + month + ":" + type;
    }

    public void start(Instant now) {
        if (status != Status.PENDING) throw new IllegalStateException("대기 작업만 실행할 수 있습니다.");
        status = Status.RUNNING; startedAt = now;
    }

    public void succeed(UUID proposalId, UUID costForecastId, Instant dataAsOf, Instant now) {
        if (status != Status.RUNNING) throw new IllegalStateException("실행 중인 작업만 완료할 수 있습니다.");
        this.proposalId = proposalId; this.costForecastId = costForecastId; this.dataAsOf = dataAsOf;
        status = Status.SUCCEEDED; completedAt = now; activeKey = null;
    }

    public void fail(String code, String message, Instant now) {
        if (status != Status.RUNNING) throw new IllegalStateException("실행 중인 작업만 실패 처리할 수 있습니다.");
        status = Status.FAILED; failureCode = code; failureMessage = message; completedAt = now; activeKey = null;
    }

    public void linkRetry(UUID nextJobId) { this.retryJobId = nextJobId; }
}
