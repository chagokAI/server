package com.skala.chagok.domain.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.box.repository.BoxSpecRepository;
import com.skala.chagok.domain.product.entity.MatchStatus;
import com.skala.chagok.domain.product.entity.Product;
import com.skala.chagok.global.config.MatchingProperties;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 비스듬한 배치 정책이 꺼져 있으면 기존 동작과 같고, 켜졌을 때만 TILTED 후보가 생기는지 확인한다. */
class ProductMatchingServiceTest {

    private static ProductMatchingService service(boolean tilted) {
        return new ProductMatchingService(mock(BoxSpecRepository.class),
                new MatchingProperties(new MatchingProperties.Tilted(tilted, 0)));
    }

    private static Product longThinProduct() {
        return Product.builder().id(UUID.randomUUID()).widthMm(520).depthMm(20).heightMm(20)
                .matchStatus(MatchStatus.UNMATCHED).isActive(true).build();
    }

    private static BoxSpec box(String code, int w, int d, int h) {
        return BoxSpec.builder().id(UUID.randomUUID()).code(code).name(code).sizeClass('M')
                .innerWidthMm(w).innerDepthMm(d).innerHeightMm(h).isActive(true).build();
    }

    @Test void policyOffKeepsRightAngleOnlyBehaviour() {
        var product = longThinProduct();
        var boxes = List.of(box("M-9", 450, 450, 100));
        assertThat(service(false).candidates(product, boxes)).isEmpty();
        service(false).applyBestCandidate(product, boxes);
        assertThat(product.getMatchStatus()).isEqualTo(MatchStatus.UNMATCHED);
    }

    @Test void policyOnAddsTiltedCandidatesAndAlwaysMarksThemForReview() {
        var product = longThinProduct();
        var boxes = List.of(box("M-9", 450, 450, 100));
        var candidates = service(true).candidates(product, boxes);
        assertThat(candidates).singleElement().satisfies(c -> assertThat(c.source()).isEqualTo("TILTED"));
        service(true).applyBestCandidate(product, boxes);
        assertThat(product.getMatchSource()).isEqualTo("TILTED");
        assertThat(product.getMatchStatus()).isEqualTo(MatchStatus.REVIEW);
    }

    @Test void tiltedCandidatesCompeteOnFillRateWithRightAngleFits() {
        var product = longThinProduct();
        var snug = box("L-9", 530, 30, 30);
        var boxes = List.of(box("M-9", 450, 450, 100), snug);
        service(true).applyBestCandidate(product, boxes);
        assertThat(product.getMatchedBoxSpec()).isSameAs(snug);
        assertThat(product.getMatchSource()).isEqualTo("DIMENSIONS");
    }
}
