package com.skala.chagok.domain.purchase.entity;

import com.skala.chagok.domain.procurement.entity.ProcurementProposal;
import com.skala.chagok.domain.supplier.entity.Supplier;
import com.skala.chagok.domain.user.entity.AppUser;
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
import jakarta.persistence.Index;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "purchase_order", indexes = @Index(name = "ix_purchase_warehouse_created", columnList = "warehouse_id,created_at"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    @Column(name = "po_no", length = 20)
    private String poNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "proposal_id")
    private ProcurementProposal proposal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "requested_delivery_date")
    private LocalDate requestedDeliveryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by")
    private AppUser requestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private AppUser approver;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @CreationTimestamp
    @Column(name = "created_at", columnDefinition = "timestamptz")
    private Instant createdAt;

    @Column(name = "approved_at", columnDefinition = "timestamptz")
    private Instant approvedAt;

    @Column(name = "received_at", columnDefinition = "timestamptz")
    private Instant receivedAt;

    @Version
    @Column(name = "version")
    private Integer version;

    /**
     * 제안서를 바탕으로 승인된 발주서를 생성한다.
     * 화면에서 요청자와 승인자가 같은 사용자이므로 두 항목에 같은 사용자가 들어간다.
     */
    public static PurchaseOrder createApproved(Warehouse warehouse,
                                               String poNo,
                                               ProcurementProposal proposal,
                                               Supplier supplier,
                                               AppUser user,
                                               LocalDate requestedDeliveryDate) {
        return PurchaseOrder.builder()
                .warehouse(warehouse)
                .poNo(poNo)
                .proposal(proposal)
                .supplier(supplier)
                .status(PurchaseOrderStatus.APPROVED)
                .requestedDeliveryDate(requestedDeliveryDate)
                .requestedBy(user)
                .approver(user)
                .approvedAt(Instant.now())
                .build();
    }
}
