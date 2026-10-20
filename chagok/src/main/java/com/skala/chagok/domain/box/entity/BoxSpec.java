package com.skala.chagok.domain.box.entity;

import com.skala.chagok.domain.supplier.entity.Supplier;
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

import java.time.Instant;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "box_spec")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BoxSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @Column(name = "code", length = 20)
    private String code;

    @Column(name = "name", length = 50)
    private String name;

    @Column(name = "size_class", length = 1, columnDefinition = "char(1)")
    private Character sizeClass;

    @Column(name = "inner_width_mm")
    private Integer innerWidthMm;

    @Column(name = "inner_depth_mm")
    private Integer innerDepthMm;

    @Column(name = "inner_height_mm")
    private Integer innerHeightMm;

    @Column(name = "material", length = 50)
    private String material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "unit_price_krw")
    private Integer unitPriceKrw;

    @Column(name = "is_custom")
    private Boolean isCustom;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "description", length = 200)
    private String description;

    @Column(name = "on_hand_qty")
    private Integer onHandQty;

    @Column(name = "safety_qty")
    private Integer safetyQty;

    @Column(name = "target_qty")
    private Integer targetQty;

    @Column(name = "days_to_stockout")
    private Integer daysToStockout;

    @Column(name = "stock_updated_at", columnDefinition = "timestamptz")
    private Instant stockUpdatedAt;

    @Version
    @Column(name = "version")
    private Integer version;

    /** 재고 관리 화면의 안전 재고 수정(INV-API-02). */
    public void updateSafetyQty(Integer safetyQty) {
        this.safetyQty = safetyQty;
    }

    public void update(String code, String name, Character sizeClass,
                       Integer innerWidthMm, Integer innerDepthMm, Integer innerHeightMm,
                       String material, Supplier supplier, Integer unitPriceKrw,
                       Boolean isCustom, Boolean isActive, String description) {
        if (code != null) {
            this.code = code;
        }
        if (name != null) {
            this.name = name;
        }
        if (sizeClass != null) {
            this.sizeClass = sizeClass;
        }
        if (innerWidthMm != null) {
            this.innerWidthMm = innerWidthMm;
        }
        if (innerDepthMm != null) {
            this.innerDepthMm = innerDepthMm;
        }
        if (innerHeightMm != null) {
            this.innerHeightMm = innerHeightMm;
        }
        if (material != null) {
            this.material = material;
        }
        if (supplier != null) {
            this.supplier = supplier;
        }
        if (unitPriceKrw != null) {
            this.unitPriceKrw = unitPriceKrw;
        }
        if (isCustom != null) {
            this.isCustom = isCustom;
        }
        if (isActive != null) {
            this.isActive = isActive;
        }
        if (description != null) {
            this.description = description;
        }
    }
}
