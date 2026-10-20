package com.skala.chagok.domain.purchase.entity;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.dto.BoxSnapshot;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "purchase_order_item")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PurchaseOrderItem {

    @Builder.Default
    @EmbeddedId
    private PurchaseOrderItemId id = new PurchaseOrderItemId();

    @MapsId("purchaseOrderId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @MapsId("boxSpecId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "box_spec_id", nullable = false)
    private BoxSpec boxSpec;

    @Column(name = "qty")
    private Integer qty;

    @Column(name = "unit_price_krw")
    private Integer unitPriceKrw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "box_snapshot", columnDefinition = "jsonb")
    private BoxSnapshot boxSnapshot;
}
