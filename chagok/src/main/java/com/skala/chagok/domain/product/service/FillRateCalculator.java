package com.skala.chagok.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * 수동 박스 지정(MATCH-API-04)용 계산. 회전을 허용해 세 변을 정렬한 뒤 비교한다.
 */
final class FillRateCalculator {

    private FillRateCalculator() {
    }

    static boolean fits(int[] productDims, int[] boxDims) {
        int[] product = productDims.clone();
        int[] box = boxDims.clone();
        Arrays.sort(product);
        Arrays.sort(box);
        for (int i = 0; i < product.length; i++) {
            if (product[i] > box[i]) {
                return false;
            }
        }
        return true;
    }

    /** 상품 부피 ÷ 박스 내부 부피 × 100, 소수 둘째 자리 반올림. */
    static BigDecimal fillRatePct(int[] productDims, int[] boxDims) {
        BigDecimal productVolume = volume(productDims);
        BigDecimal boxVolume = volume(boxDims);
        return productVolume.multiply(BigDecimal.valueOf(100))
                .divide(boxVolume, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal volume(int[] dims) {
        return BigDecimal.valueOf((long) dims[0] * dims[1] * dims[2]);
    }
}
