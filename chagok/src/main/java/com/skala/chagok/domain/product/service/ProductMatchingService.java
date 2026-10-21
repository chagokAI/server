package com.skala.chagok.domain.product.service;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.product.dto.MatchCandidateResponse;
import com.skala.chagok.domain.product.entity.Product;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ProductMatchingService {
    private final BoxSpecRepository boxes;

    /** 자동 매칭의 match_source. 후보의 source와 같다. */
    public static final String SOURCE_DIMENSIONS = "DIMENSIONS";
    private static final String SOURCE_MANUAL = "MANUAL";

    public List<MatchCandidateResponse> candidates(Product product) {
        return candidates(product, activeBoxes(product));
    }

    /** 후보 목록. 1순위(recommended)는 applyBestCandidate가 저장하는 박스와 항상 같다. */
    public List<MatchCandidateResponse> candidates(Product product, List<BoxSpec> activeBoxes) {
        var ranked = ranked(product, activeBoxes);
        var result = new ArrayList<MatchCandidateResponse>();
        for (int i = 0; i < ranked.size(); i++) {
            var box = ranked.get(i);
            result.add(new MatchCandidateResponse(BoxSnapshot.from(box), rate(product, box), i == 0,
                    product.getMatchedBoxSpec() != null && product.getMatchedBoxSpec().getId().equals(box.getId()),
                    SOURCE_DIMENSIONS));
        }
        return result;
    }

    public void applyBestCandidate(Product product) {
        applyBestCandidate(product, activeBoxes(product));
    }

    /**
     * 후보 1순위를 상품 매칭으로 저장한다(match_source=DIMENSIONS, 상태는 refreshMatch의 85% 기준).
     * 운영자가 직접 지정한 박스(MANUAL)는 바꾸지 않고 현재 박스만 다시 검증한다. 들어가는 박스가 없으면 UNMATCHED.
     */
    public void applyBestCandidate(Product product, List<BoxSpec> activeBoxes) {
        if (SOURCE_MANUAL.equals(product.getMatchSource())) {
            refreshCurrent(product);
            return;
        }
        var ranked = ranked(product, activeBoxes);
        if (ranked.isEmpty()) {
            product.clearMatch();
            return;
        }
        var best = ranked.getFirst();
        product.applyAutoMatch(best, rate(product, best), SOURCE_DIMENSIONS);
    }

    /** 같은 센터의 활성 박스. 일괄 재매칭은 한 번 읽어 여러 상품에 재사용한다. */
    public List<BoxSpec> activeBoxes(Product product) {
        return boxes.findByWarehouseIdAndIsActiveTrue(product.getWarehouse().getId());
    }

    /** 들어가는 박스를 충전율 내림차순, 동률은 박스 코드 순(BoxSnapshot.ORDER)으로 정렬한다. */
    private List<BoxSpec> ranked(Product product, List<BoxSpec> activeBoxes) {
        if (dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm()) == null) return List.of();
        return activeBoxes.stream()
                .filter(box -> fits(product, box))
                .sorted(Comparator.comparing((BoxSpec box) -> rate(product, box)).reversed()
                        .thenComparing(BoxSnapshot::from, BoxSnapshot.ORDER))
                .toList();
    }

    public void refreshCurrent(Product product) {
        var box = product.getMatchedBoxSpec();
        if (box == null) return;
        if (!Boolean.TRUE.equals(box.getIsActive()) || !fits(product, box)) product.clearMatch();
        else product.refreshMatch(rate(product, box));
    }

    public static boolean fits(Product product, BoxSpec box) {
        int[] p = dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm());
        int[] b = dimensions(box.getInnerWidthMm(), box.getInnerDepthMm(), box.getInnerHeightMm());
        return p != null && b != null && FillRateCalculator.fits(p, b);
    }

    public static java.math.BigDecimal rate(Product product, BoxSpec box) {
        return FillRateCalculator.fillRatePct(dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm()),
                dimensions(box.getInnerWidthMm(), box.getInnerDepthMm(), box.getInnerHeightMm()));
    }

    private static int[] dimensions(Integer w, Integer d, Integer h) {
        return w == null || d == null || h == null || w <= 0 || d <= 0 || h <= 0 ? null : new int[]{w, d, h};
    }
}
