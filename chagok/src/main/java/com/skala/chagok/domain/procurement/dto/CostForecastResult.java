package com.skala.chagok.domain.procurement.dto;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import java.math.BigDecimal;
import java.util.List;

/** 금액은 VAT 별도 KRW. 비교 값과 절감액은 계산 근거가 저장된 경우에만 채운다. */
public record CostForecastResult(Long packagingCostKrw, Long packagingSavingsKrw, Long purchaseCostKrw,
                                 Comparison previousMonth, Comparison previousYear, List<Item> items,
                                 List<String> insights) {
    public record Comparison(String baseMonth, Long baseAmountKrw, Long changeAmountKrw, BigDecimal changePct) {}
    public record Item(BoxSnapshot boxSpec, Integer forecastUsageQty, Integer proposedQty, Integer unitPriceKrw,
                       Long packagingCostKrw, Long purchaseCostKrw) {}
}
