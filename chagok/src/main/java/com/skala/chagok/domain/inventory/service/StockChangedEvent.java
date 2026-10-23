package com.skala.chagok.domain.inventory.service;

import java.util.UUID;

/** 입고·실사·라이브 초기화의 확정 후 현재고를 다시 읽도록 알린다. */
public record StockChangedEvent(UUID warehouseId) {}
