package com.skala.chagok.domain.inventory.entity;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;
import lombok.*;

/** 입고·실사 조정·포장 사용의 수량 변경 이력. 관측 이벤트와 연결해 중복 사용을 막는다. */
@Entity @Table(name = "inventory_movement", uniqueConstraints = @UniqueConstraint(
        name = "uq_inventory_request", columnNames = {"warehouse_id", "request_id"}))
@Getter @Builder @NoArgsConstructor(access = AccessLevel.PROTECTED) @AllArgsConstructor
public class InventoryMovement {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "warehouse_id", nullable = false) private UUID warehouseId;
    @Column(name = "box_spec_id", nullable = false) private UUID boxSpecId;
    @Column(name = "purchase_order_id") private UUID purchaseOrderId;
    @Column(name = "request_id", nullable = false) private UUID requestId;
    @Column(nullable = false, length = 20) private String type;
    @Column(name = "delta_qty") private Long deltaQty;
    @Column(name = "before_qty") private Integer beforeQty;
    @Column(name = "after_qty", nullable = false) private Integer afterQty;
    @Column(name = "occurred_on", nullable = false) private LocalDate occurredOn;
    @Column(length = 500) private String memo;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
