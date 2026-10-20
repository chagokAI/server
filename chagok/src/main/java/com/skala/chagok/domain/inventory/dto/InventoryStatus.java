package com.skala.chagok.domain.inventory.dto;

import com.skala.chagok.domain.box.entity.BoxSpec;

/**
 * 규격별 재고 상태. DB에 저장하지 않고 조회할 때마다 계산한다.
 * 부족을 먼저 판정하고, 그다음 주의를 본다.
 */
public enum InventoryStatus {
    /** 충분 */
    SUFFICIENT,
    /** 현재 재고 또는 안전 재고가 확인되지 않음 */
    UNKNOWN,
    /** 주의: 예상 소진일이 (공급사 리드타임 + 여유일) 이하 → 지금 발주하지 않으면 입고 전에 바닥난다 */
    CAUTION,
    /** 부족: 현재 재고가 안전 재고 미만 */
    SHORTAGE;

    public static InventoryStatus of(BoxSpec boxSpec, int cautionBufferDays) {
        if (boxSpec.getOnHandQty() == null || boxSpec.getSafetyQty() == null) return UNKNOWN;
        int onHand = boxSpec.getOnHandQty();
        if (boxSpec.getSafetyQty() != null && onHand < boxSpec.getSafetyQty()) {
            return SHORTAGE;
        }

        Integer days = boxSpec.getDaysToStockout();
        int leadTime = boxSpec.getSupplier() == null || boxSpec.getSupplier().getLeadTimeDays() == null
                ? 0 : boxSpec.getSupplier().getLeadTimeDays();
        if (days != null && days <= leadTime + cautionBufferDays) {
            return CAUTION;
        }
        return SUFFICIENT;
    }
}
