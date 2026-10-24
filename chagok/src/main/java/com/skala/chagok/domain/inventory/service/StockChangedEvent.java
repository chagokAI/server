package com.skala.chagok.domain.inventory.service;

import java.util.UUID;

/**
 * 입고·실사·라이브 초기화의 확정 후 현재고를 다시 읽도록 알린다.
 *
 * @param liveReset 라이브 초기화로 바뀐 재고인지. 화면은 이때만 라이브 장면을 비운다
 */
public record StockChangedEvent(UUID warehouseId, boolean liveReset) {
    public StockChangedEvent(UUID warehouseId) { this(warehouseId, false); }
}
