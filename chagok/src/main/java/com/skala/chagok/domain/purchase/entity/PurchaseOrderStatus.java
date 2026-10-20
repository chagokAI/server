package com.skala.chagok.domain.purchase.entity;

public enum PurchaseOrderStatus {

    /** 승인 대기 */
    PENDING,

    /** 승인 완료 */
    APPROVED,

    /** 반려 */
    REJECTED,

    /** 입고 완료 */
    RECEIVED
}
