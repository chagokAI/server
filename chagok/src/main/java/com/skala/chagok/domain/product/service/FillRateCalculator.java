package com.skala.chagok.domain.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/**
 * 박스 수용 판정과 충전율 계산의 단일 구현. 매칭 후보·자동 적용·수동 지정이 모두 이 클래스를 쓴다.
 * 기본 판정 fits()는 90도 회전만 허용하고, fitsTilted()는 정책이 켜졌을 때만 쓰는 비스듬한 배치 판정이다.
 */
public final class FillRateCalculator {

    private FillRateCalculator() {
    }

    public static boolean fits(int[] productDims, int[] boxDims) {
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

    /**
     * 한 평면 안에서 비스듬히 기울여 넣는 배치까지 허용한 판정. 상품 각 변에 clearanceMm를 더한 뒤,
     * 상품 변 (p, q, r)과 박스 변 (a, b, c)의 모든 순서 중 하나라도 r ≤ c이고 p×q가 a×b 안에 들어가면 true.
     * 한 평면만 기울이는 보수적 판정이라 통과하면 실제로 들어간다(통과하지 못해도 못 들어간다는 뜻은 아니다).
     */
    public static boolean fitsTilted(int[] productDims, int[] boxDims, int clearanceMm) {
        double[] product = Arrays.stream(productDims).mapToDouble(d -> d + clearanceMm).toArray();
        for (int[] pi : PERMUTATIONS) {
            for (int[] bi : PERMUTATIONS) {
                if (product[pi[2]] <= boxDims[bi[2]]
                        && rectFits(product[pi[0]], product[pi[1]], boxDims[bi[0]], boxDims[bi[1]])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int[][] PERMUTATIONS = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    /**
     * 직사각형 p×q가 a×b 안에 (기울여서라도) 들어가는지. Carver 조건:
     * p ≥ q, a ≥ b로 정렬한 뒤 (p ≤ a, q ≤ b)이거나,
     * p > a, q ≤ b이고 b ≥ (2pqa + (p² − q²)·√(p² + q² − a²)) / (p² + q²)이면 들어간다.
     */
    static boolean rectFits(double p, double q, double a, double b) {
        if (p < q) {
            double t = p; p = q; q = t;
        }
        if (a < b) {
            double t = a; a = b; b = t;
        }
        if (p <= a && q <= b) {
            return true;
        }
        double diagonalSq = p * p + q * q;
        // p > a이면 p² + q² ≥ a²가 항상 성립하지만, 제곱근 인자가 음수가 되지 않도록 함께 확인한다.
        if (p > a && q <= b && diagonalSq >= a * a) {
            double required = (2 * p * q * a + (p * p - q * q) * Math.sqrt(diagonalSq - a * a)) / diagonalSq;
            return b >= required;
        }
        return false;
    }

    /** 상품 부피 ÷ 박스 내부 부피 × 100, 소수 둘째 자리 반올림. */
    public static BigDecimal fillRatePct(int[] productDims, int[] boxDims) {
        BigDecimal productVolume = volume(productDims);
        BigDecimal boxVolume = volume(boxDims);
        return productVolume.multiply(BigDecimal.valueOf(100))
                .divide(boxVolume, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal volume(int[] dims) {
        return BigDecimal.valueOf(dims[0]).multiply(BigDecimal.valueOf(dims[1])).multiply(BigDecimal.valueOf(dims[2]));
    }
}
