package com.skala.chagok.domain.event.entity;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.domain.warehouse.entity.Warehouse;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Index;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "item_event", indexes = {
        @Index(name = "ix_event_warehouse_time", columnList = "warehouse_id,occurred_at"),
        @Index(name = "ix_event_warehouse_status", columnList = "warehouse_id,status")})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ItemEvent {

    @EmbeddedId
    private ItemEventId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "status", length = 10)
    private String status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "box_spec_id")
    private BoxSpec boxSpec;

    @Column(name = "fill_rate_pct", precision = 5, scale = 2)
    private BigDecimal fillRatePct;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "placement", columnDefinition = "jsonb")
    private Map<String, Object> placement;

    /** Worker 결과의 치수·출처. 배치 좌표와 구분해 저장한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "measurement", columnDefinition = "jsonb")
    private Map<String, Object> measurement;

    /** 신규 촬영의 원본 경로. 기존 덤프의 이력에는 없어도 된다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_refs", columnDefinition = "jsonb")
    private List<String> imageRefs;

    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;
}
