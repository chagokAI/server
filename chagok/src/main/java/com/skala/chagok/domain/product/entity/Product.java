package com.skala.chagok.domain.product.entity;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.category.entity.Category;
import com.skala.chagok.domain.warehouse.entity.Warehouse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @Column(name = "code", length = 30)
    private String code;

    @Column(name = "name", length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "width_mm")
    private Integer widthMm;

    @Column(name = "depth_mm")
    private Integer depthMm;

    @Column(name = "height_mm")
    private Integer heightMm;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 384)
    @Column(name = "embedding", columnDefinition = "vector(384)")
    private float[] embedding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "matched_box_spec_id")
    private BoxSpec matchedBoxSpec;

    @Column(name = "fill_rate_pct", precision = 5, scale = 2)
    private BigDecimal fillRatePct;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", length = 20)
    private MatchStatus matchStatus;

    @Column(name = "match_source", length = 10)
    private String matchSource;

    /** 운영자가 박스를 직접 지정한 사유. match_source=MANUAL일 때만 값이 있다. */
    @Column(name = "match_override_reason", length = 200)
    private String matchOverrideReason;

    @Column(name = "is_active")
    private Boolean isActive;

    @Version
    @Column(name = "version")
    private Integer version;

    /** 전달된 값만 반영한다. 매칭 상태는 박스를 직접 지정할 때만 바뀐다({@link #overrideMatch}). */
    public void update(String name, Category category,
                       Integer widthMm, Integer depthMm, Integer heightMm, String imageUrl) {
        if (name != null) {
            this.name = name;
        }
        if (category != null) {
            this.category = category;
        }
        if (widthMm != null) {
            this.widthMm = widthMm;
        }
        if (depthMm != null) {
            this.depthMm = depthMm;
        }
        if (heightMm != null) {
            this.heightMm = heightMm;
        }
        if (imageUrl != null) {
            this.imageUrl = imageUrl;
        }
    }

    /** 운영자가 박스를 직접 지정한 경우. match_source=MANUAL로 자동 매칭 결과와 구분한다. */
    public void overrideMatch(BoxSpec boxSpec, BigDecimal fillRatePct, String reason) {
        this.matchedBoxSpec = boxSpec;
        this.fillRatePct = fillRatePct;
        this.matchStatus = MatchStatus.MATCHED;
        this.matchSource = "MANUAL";
        this.matchOverrideReason = reason;
    }

    /** 상품 삭제는 소프트 삭제다. 이벤트·이력이 상품을 참조하므로 행을 지우지 않는다. */
    public void deactivate() {
        this.isActive = false;
    }
}
