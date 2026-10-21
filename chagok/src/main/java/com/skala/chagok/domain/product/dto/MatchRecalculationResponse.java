package com.skala.chagok.domain.product.dto;

/**
 * 일괄 재매칭 결과. targetCount는 매칭 박스가 없던 활성 상품 수이고, 나머지는 처리 후 상태별 개수다.
 * 치수가 없거나 들어가는 박스가 없으면 unmatchedCount에 들어간다.
 */
public record MatchRecalculationResponse(int targetCount, int matchedCount, int reviewCount, int unmatchedCount) {
}
