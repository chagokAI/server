package com.skala.chagok.domain.product.entity;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.warehouse.entity.Warehouse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "category", length = 50)
    private String category;

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

    @Column(name = "match_status", length = 20)
    private String matchStatus;

    @Column(name = "match_source", length = 10)
    private String matchSource;

    @Column(name = "is_active")
    private Boolean isActive;

    @Version
    @Column(name = "version")
    private Integer version;
}
