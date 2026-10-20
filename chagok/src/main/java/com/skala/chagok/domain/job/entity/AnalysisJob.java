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
}
