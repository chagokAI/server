package com.skala.chagok.domain.job.service;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.job.config.JobProperties;
import java.math.*;
import java.time.YearMonth;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** 실측 소모량이 준비되기 전의 명시적인 관측 기반 참고 계산. */
@Component @RequiredArgsConstructor
public class ObservationBaselineCalculator {
    private final JobProperties properties;
    public record Line(BoxSpec spec, BoxSnapshot snapshot, int forecastQty, int proposedQty,
                       int unitPriceKrw, long packagingCostKrw, long purchaseCostKrw, long observations,
                       int safetyQty, String safetySource) {}

    public Line calculate(BoxSpec spec, long observations, YearMonth targetMonth) {
        if (spec.getOnHandQty() == null || spec.getOnHandQty() < 0
                || (spec.getSafetyQty() != null && spec.getSafetyQty() < 0)
                || (spec.getSafetyQty() == null && (spec.getSafetyDays() == null || spec.getSafetyDays() < 0))
                || spec.getUnitPriceKrw() == null || spec.getUnitPriceKrw() < 0)
            throw new JobCalculationException("INCOMPLETE_BOX_DATA", "현재 재고·안전 재고·단가를 확인하세요. 규격=" + spec.getCode());
        try {
            int forecast = BigDecimal.valueOf(observations).multiply(BigDecimal.valueOf(targetMonth.lengthOfMonth()))
                    .divide(BigDecimal.valueOf(properties.lookbackDays()), 0, RoundingMode.CEILING).intValueExact();
            int safety = spec.getSafetyQty() != null ? spec.getSafetyQty()
                    : BigDecimal.valueOf(observations).multiply(BigDecimal.valueOf(spec.getSafetyDays()))
                            .divide(BigDecimal.valueOf(properties.lookbackDays()), 0, RoundingMode.CEILING).intValueExact();
            long shortage = Math.max(0L, (long) forecast + safety - spec.getOnHandQty());
            int proposed = Math.toIntExact(((shortage + properties.orderUnit() - 1) / properties.orderUnit()) * properties.orderUnit());
            return new Line(spec, BoxSnapshot.from(spec), forecast, proposed, spec.getUnitPriceKrw(),
                    Math.multiplyExact((long) forecast, spec.getUnitPriceKrw()),
                    Math.multiplyExact((long) proposed, spec.getUnitPriceKrw()), observations,
                    safety, spec.getSafetyQty() != null ? "CONFIGURED" : "DAYS");
        } catch (ArithmeticException e) {
            throw new JobCalculationException("CALCULATION_OVERFLOW", "계산 수량·금액의 범위를 초과했습니다.");
        }
    }
}
