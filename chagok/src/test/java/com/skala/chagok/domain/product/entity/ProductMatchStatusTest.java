package com.skala.chagok.domain.product.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** 상품 마스터 상태와 실제 포장 판정이 함께 쓰는 매칭 상태 기준을 경계값으로 확인한다. */
class ProductMatchStatusTest {

    @Test void automaticMatchBecomesMatchedFromTheMinimumFillRate() {
        assertThat(Product.matchStatusFor("DIMENSIONS", new BigDecimal("49.99"))).isEqualTo(MatchStatus.REVIEW);
        assertThat(Product.matchStatusFor("DIMENSIONS", new BigDecimal("50.00"))).isEqualTo(MatchStatus.MATCHED);
        assertThat(Product.matchStatusFor(null, new BigDecimal("68.87"))).isEqualTo(MatchStatus.MATCHED);
    }

    @Test void manualAndTiltedSourcesIgnoreTheFillRate() {
        assertThat(Product.matchStatusFor("MANUAL", new BigDecimal("1.00"))).isEqualTo(MatchStatus.MATCHED);
        assertThat(Product.matchStatusFor("TILTED", new BigDecimal("99.00"))).isEqualTo(MatchStatus.REVIEW);
    }
}
