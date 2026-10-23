package com.skala.chagok.domain.product.service;

import com.skala.chagok.domain.box.dto.BoxSnapshot;
import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.product.dto.MatchCandidateResponse;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.global.config.MatchingProperties;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ProductMatchingService {
    private final BoxSpecRepository boxes;
    private final MatchingProperties policy;

    /** 90도 회전으로 들어가는 자동 매칭. 후보의 source와 match_source에 쓴다. */
    public static final String SOURCE_DIMENSIONS = "DIMENSIONS";
    /** 비스듬히 넣어야 들어가는 자동 매칭(정책이 켜졌을 때만). 충전율과 관계없이 REVIEW다. */
    public static final String SOURCE_TILTED = "TILTED";
    private static final String SOURCE_MANUAL = "MANUAL";

    public List<MatchCandidateResponse> candidates(Product product) {
        return candidates(product, activeBoxes(product));
    }

    /** 실제 포장 후보는 확인된 현재고가 있는 박스만 사용한다. 상품 기본 추천은 바꾸지 않는다. */
    public List<MatchCandidateResponse> packingCandidates(Product product) {
        var available = activeBoxes(product).stream()
                .filter(box -> box.getOnHandQty() != null && box.getOnHandQty() > 0)
                .filter(box -> !SOURCE_MANUAL.equals(product.getMatchSource())
                        || product.getMatchedBoxSpec() != null
                        && product.getMatchedBoxSpec().getId().equals(box.getId()))
                .toList();
        return candidates(product, available);
    }

    /** 후보 목록. 1순위(recommended)는 applyBestCandidate가 저장하는 박스와 항상 같다. */
    public List<MatchCandidateResponse> candidates(Product product, List<BoxSpec> activeBoxes) {
        var ranked = ranked(product, activeBoxes);
        var result = new ArrayList<MatchCandidateResponse>();
        for (int i = 0; i < ranked.size(); i++) {
            var candidate = ranked.get(i);
            var box = candidate.box();
            result.add(new MatchCandidateResponse(BoxSnapshot.from(box), rate(product, box), i == 0,
                    product.getMatchedBoxSpec() != null && product.getMatchedBoxSpec().getId().equals(box.getId()),
                    candidate.source()));
        }
        return result;
    }

    public void applyBestCandidate(Product product) {
        applyBestCandidate(product, activeBoxes(product));
    }

    /**
     * 후보 1순위를 상품 매칭으로 저장한다(match_source는 후보의 source, 상태는 refreshMatch 기준).
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
        product.applyAutoMatch(best.box(), rate(product, best.box()), best.source());
    }

    /** 같은 센터의 활성 박스. 일괄 재매칭은 한 번 읽어 여러 상품에 재사용한다. */
    public List<BoxSpec> activeBoxes(Product product) {
        return boxes.findByWarehouseIdAndIsActiveTrue(product.getWarehouse().getId());
    }

    private record Candidate(BoxSpec box, String source) {
    }

    /** 들어가는 박스를 충전율 내림차순, 동률은 박스 코드 순(BoxSnapshot.ORDER)으로 정렬한다. */
    private List<Candidate> ranked(Product product, List<BoxSpec> activeBoxes) {
        if (dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm()) == null) return List.of();
        return activeBoxes.stream()
                .flatMap(box -> fitSource(product, box).map(source -> new Candidate(box, source)).stream())
                .sorted(Comparator.comparing((Candidate c) -> rate(product, c.box())).reversed()
                        .thenComparing(c -> BoxSnapshot.from(c.box()), BoxSnapshot.ORDER))
                .toList();
    }

    /**
     * 박스 크기나 사용 여부가 바뀐 뒤 현재 매칭을 다시 검증한다. 안 들어가거나 비활성이면 UNMATCHED.
     * 비스듬한 배치(TILTED)로 들어가게 됐거나 TILTED였던 자동 매칭은 배치 방식을 다시 정한다.
     * 그 외(직접 지정, 기존 자동 매칭)는 출처를 그대로 두고 비율·상태만 갱신한다. 정책이 꺼져 있으면 기존 동작과 같다.
     */
    public void refreshCurrent(Product product) {
        var box = product.getMatchedBoxSpec();
        if (box == null) return;
        var source = Boolean.TRUE.equals(box.getIsActive()) ? fitSource(product, box) : Optional.<String>empty();
        if (source.isEmpty()) {
            product.clearMatch();
        } else if (!SOURCE_MANUAL.equals(product.getMatchSource())
                && (SOURCE_TILTED.equals(source.get()) || SOURCE_TILTED.equals(product.getMatchSource()))) {
            product.applyAutoMatch(box, rate(product, box), source.get());
        } else {
            product.refreshMatch(rate(product, box));
        }
    }

    /** 90도 회전으로 들어가면 DIMENSIONS, 정책이 켜져 있고 비스듬히 넣어야 들어가면 TILTED, 아니면 empty. */
    private Optional<String> fitSource(Product product, BoxSpec box) {
        if (fits(product, box)) return Optional.of(SOURCE_DIMENSIONS);
        if (!policy.tilted().enabled()) return Optional.empty();
        int[] p = dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm());
        int[] b = dimensions(box.getInnerWidthMm(), box.getInnerDepthMm(), box.getInnerHeightMm());
        return p != null && b != null && FillRateCalculator.fitsTilted(p, b, policy.tilted().clearanceMm())
                ? Optional.of(SOURCE_TILTED) : Optional.empty();
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
