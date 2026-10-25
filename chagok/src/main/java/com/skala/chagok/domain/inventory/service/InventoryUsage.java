package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.inventory.dto.InventorySummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

/**
 * 이번 달 저장 제안서의 규격별 참고 수량. 실제 소모량이 아니다.
 *
 * @param monthly  규격 id → 월 참고 수량. 제안서가 없으면 비어 있다
 * @param complete 제안서의 모든 품목에 참고 수량이 있는지. 주간 합계는 이때만 신뢰한다
 */
public record InventoryUsage(YearMonth month, Map<UUID, Integer> monthly, boolean complete,
                             InventorySummary.ForecastSource source) {

    public BigDecimal daily(UUID boxSpecId) {
        Integer qty = monthly.get(boxSpecId);
        return qty == null ? null
                : BigDecimal.valueOf(qty).divide(BigDecimal.valueOf(month.lengthOfMonth()), 4, RoundingMode.HALF_UP);
    }

    public Instant dataAsOf() {
        return source == null ? null : source.dataAsOf();
    }

    public Integer weekly() {
        if (!complete) {
            return null;
        }
        long qty = monthly.values().stream().mapToLong(Integer::longValue).sum();
        return BigDecimal.valueOf(qty).multiply(BigDecimal.valueOf(7))
                .divide(BigDecimal.valueOf(month.lengthOfMonth()), 0, RoundingMode.HALF_UP).intValueExact();
    }
}
