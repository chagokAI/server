package com.skala.chagok.job;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.job.config.JobProperties;
import com.skala.chagok.domain.job.entity.AnalysisJob;
import com.skala.chagok.domain.job.service.*;
import com.skala.chagok.domain.warehouse.entity.Warehouse;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JobCalculationTests {
    private final ObservationBaselineCalculator calculator = new ObservationBaselineCalculator(new JobProperties(28, 100, Duration.ofMinutes(5)));

    @Test void extrapolatesObservedDailyAverageAndRoundsOrdersUpToHundreds() {
        var box = BoxSpec.builder().code("S-1").onHandQty(10).safetyQty(5).unitPriceKrw(180).build();
        var line = calculator.calculate(box, 28, YearMonth.of(2026, 11));
        assertThat(line.forecastQty()).isEqualTo(30);
        assertThat(line.proposedQty()).isEqualTo(100);
        assertThat(line.packagingCostKrw()).isEqualTo(5400);
        assertThat(line.purchaseCostKrw()).isEqualTo(18000);
    }

    @Test void enoughStockProducesZeroOrderAndDoesNotMutateStock() {
        var box = BoxSpec.builder().onHandQty(500).safetyQty(100).unitPriceKrw(180).build();
        assertThat(calculator.calculate(box, 28, YearMonth.of(2026, 11)).proposedQty()).isZero();
        assertThat(box.getOnHandQty()).isEqualTo(500);
    }

    @Test void zeroObservedForOneBoxIsZeroReferenceDemand() {
        var box = BoxSpec.builder().onHandQty(0).safetyQty(20).unitPriceKrw(100).build();
        var line = calculator.calculate(box, 0, YearMonth.of(2026, 11));
        assertThat(line.forecastQty()).isZero();
        assertThat(line.proposedQty()).isEqualTo(100);
    }

    @Test void derivesMissingSafetyQuantityFromRegisteredDaysWithoutMutatingPolicy() {
        var box = BoxSpec.builder().onHandQty(0).safetyDays(7).unitPriceKrw(100).build();
        var line = calculator.calculate(box, 29, YearMonth.of(2026, 11));
        assertThat(line.safetyQty()).isEqualTo(8);
        assertThat(line.safetySource()).isEqualTo("DAYS");
        assertThat(box.getSafetyQty()).isNull();
        assertThat(box.getSafetyDays()).isEqualTo(7);
    }

    @Test void explicitZeroSafetyQuantityTakesPriorityOverDays() {
        var box = BoxSpec.builder().onHandQty(0).safetyQty(0).safetyDays(7).unitPriceKrw(100).build();
        var line = calculator.calculate(box, 28, YearMonth.of(2026, 11));
        assertThat(line.safetyQty()).isZero();
        assertThat(line.safetySource()).isEqualTo("CONFIGURED");
    }

    @Test void doesNotSubstituteUnknownStockOrPriceWithZero() {
        assertThatThrownBy(() -> calculator.calculate(BoxSpec.builder().code("S-1").build(), 28, YearMonth.of(2026, 11)))
                .isInstanceOfSatisfying(JobCalculationException.class, e -> assertThat(e.code()).isEqualTo("INCOMPLETE_BOX_DATA"));
    }

    @Test void rejectsOverflowRatherThanWritingWrappedQuantities() {
        var box = BoxSpec.builder().onHandQty(0).safetyQty(0).unitPriceKrw(1).build();
        assertThatThrownBy(() -> calculator.calculate(box, Long.MAX_VALUE, YearMonth.of(2026, 11)))
                .isInstanceOfSatisfying(JobCalculationException.class, e -> assertThat(e.code()).isEqualTo("CALCULATION_OVERFLOW"));
    }

    @Test void terminalJobsReleaseActiveKeyAndCannotBeCompletedTwice() {
        var now = Instant.parse("2026-10-20T00:00:00Z");
        var job = AnalysisJob.pending(Warehouse.builder().id(UUID.randomUUID()).build(), "2026-11",
                AnalysisJob.Type.PROCUREMENT_AND_COST, AnalysisJob.Trigger.MANUAL, now);
        assertThat(job.getActiveKey()).isNotNull();
        job.start(now);
        job.succeed(UUID.randomUUID(), UUID.randomUUID(), now, now.plusSeconds(1));
        assertThat(job.getActiveKey()).isNull();
        assertThatThrownBy(() -> job.succeed(null, null, now, now)).isInstanceOf(IllegalStateException.class);
    }
}
