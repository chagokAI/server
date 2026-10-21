package com.skala.chagok.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FillRateCalculatorTest {

    @Test void rectFitsAcceptsAxisAlignedAndDiagonalPlacementsOnly() {
        assertThat(FillRateCalculator.rectFits(5, 3, 6, 4)).isTrue();
        // 10×1 막대는 9.5×9.5 안에 대각선으로 들어간다.
        assertThat(FillRateCalculator.rectFits(10, 1, 9.5, 9.5)).isTrue();
        // 넓이가 거의 같은 10×9는 어떻게 기울여도 9.5×9.5에 들어가지 않는다.
        assertThat(FillRateCalculator.rectFits(10, 9, 9.5, 9.5)).isFalse();
    }

    @Test void tiltedPlacementAcceptsALongThinItemThatRightAngleRotationRejects() {
        int[] product = {520, 20, 20};
        int[] box = {450, 450, 100};
        assertThat(FillRateCalculator.fits(product, box)).isFalse();
        assertThat(FillRateCalculator.fitsTilted(product, box, 0)).isTrue();
    }

    @Test void clearanceIsAddedToEveryProductSide() {
        int[] product = {520, 20, 20};
        int[] box = {450, 450, 100};
        assertThat(FillRateCalculator.fitsTilted(product, box, 100)).isFalse();
    }

    @Test void tiltedJudgementStillAcceptsEverythingTheBasicJudgementAccepts() {
        int[] product = {120, 80, 45};
        int[] box = {125, 85, 47};
        assertThat(FillRateCalculator.fits(product, box)).isTrue();
        assertThat(FillRateCalculator.fitsTilted(product, box, 0)).isTrue();
    }
}
