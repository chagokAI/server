package com.skala.chagok.domain.procurement.entity;

import com.skala.chagok.domain.procurement.dto.CostForecastResult;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 성공한 비용 예측의 불변 결과. 조회 시 현재 단가로 과거 예측을 재계산하지 않는다. */
@Entity @Table(name = "cost_forecast", indexes = @Index(name = "ix_cost_warehouse_month", columnList = "warehouse_id,target_month,generated_at"))
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CostForecast {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "warehouse_id", nullable = false) private Warehouse warehouse;
    @Column(name = "job_id") private UUID jobId;
    @Column(name = "target_month", nullable = false, length = 7) private String targetMonth;
    @Column(name = "data_as_of") private Instant dataAsOf;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "result", columnDefinition = "jsonb", nullable = false)
    private CostForecastResult result;
}
