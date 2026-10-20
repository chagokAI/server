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

    public List<MatchCandidateResponse> candidates(Product product) {
        int[] dims = dimensions(product.getWidthMm(), product.getDepthMm(), product.getHeightMm());
        if (dims == null) return List.of();
        var sorted = boxes.findByWarehouseIdAndIsActiveTrue(product.getWarehouse().getId()).stream()
                .filter(box -> fits(product, box))
                .map(box -> new MatchCandidateResponse(BoxSnapshot.from(box), rate(product, box), false,
                        product.getMatchedBoxSpec() != null && product.getMatchedBoxSpec().getId().equals(box.getId()), "DIMENSIONS"))
                .sorted(Comparator.comparing(MatchCandidateResponse::fillRatePct).reversed()
                        .thenComparing(MatchCandidateResponse::boxSpec, BoxSnapshot.ORDER)).toList();
        var result = new ArrayList<MatchCandidateResponse>();
        for (int i = 0; i < sorted.size(); i++) {
            var candidate = sorted.get(i);
            result.add(new MatchCandidateResponse(candidate.boxSpec(), candidate.fillRatePct(), i == 0,
                    candidate.selected(), candidate.source()));
        }
        return result;
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
