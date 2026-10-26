package com.skala.chagok.domain.purchase.dto.response;

import com.skala.chagok.domain.purchase.entity.PurchaseOrder;
import com.skala.chagok.domain.purchase.entity.PurchaseOrderStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 박스 주문 발주서 생성 결과.
 *
 * @param success         성공 여부
 * @param purchaseOrderId 생성된 발주서 ID
 * @param poNo            서버가 발번한 발주번호
 * @param status          발주서 상태
 * @param approvedAt      승인 시각(승인 전에는 null)
 */
public record PurchaseOrderCreateResponse(
        boolean success,
        UUID purchaseOrderId,
        String poNo,
        PurchaseOrderStatus status,
        Instant approvedAt
) {

    public static PurchaseOrderCreateResponse from(PurchaseOrder purchaseOrder) {
        return new PurchaseOrderCreateResponse(
                true,
                purchaseOrder.getId(),
                purchaseOrder.getPoNo(),
                purchaseOrder.getStatus(),
                purchaseOrder.getApprovedAt()
        );
    }
}
