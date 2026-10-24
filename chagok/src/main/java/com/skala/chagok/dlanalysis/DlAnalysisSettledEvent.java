package com.skala.chagok.dlanalysis;

import java.util.UUID;

/** 결과 반영 트랜잭션이 확정된 뒤 화면에 알릴 이벤트 식별자다. */
public record DlAnalysisSettledEvent(String eventId, UUID warehouseId) {}
