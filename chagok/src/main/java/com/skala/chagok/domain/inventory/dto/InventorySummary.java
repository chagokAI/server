package com.skala.chagok.domain.inventory.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

/**
 * 상단 요약 카드. 검색어·상태 필터와 관계없이 센터의 활성 규격 전체 기준이다.
 *
 * @param weeklyForecastUsageQty  이번 달 제안서의 예상 소모량을 주 단위로 환산한 값. 제안서가 없으면 null
 * @param weeklyForecastChangePct 전주 대비 증감률. 근거 데이터가 없어 현재는 항상 null
 * @param avgDaysToStockout       예상 소진일이 있는 규격들의 평균(소수 첫째 자리)
 * @param lastUpdatedAt           규격별 재고 갱신 시각 중 가장 최근 값 ("마지막 업데이트")
 */
public record InventorySummary(
        long totalOnHandQty,
        int activeSpecCount,
        int shortageSpecCount,
        List<String> shortageSpecCodes,
        Integer weeklyForecastUsageQty,
        BigDecimal weeklyForecastChangePct,
        BigDecimal avgDaysToStockout,
        int targetDaysToStockout,
        OffsetDateTime lastUpdatedAt,
        ForecastSource forecastSource
) {
    public record ForecastSource(UUID proposalId, String targetMonth, Instant dataAsOf, Instant generatedAt) {}
}
