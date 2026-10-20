package com.skala.chagok.domain.product.dto;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.domain.product.entity.MatchStatus;
import com.skala.chagok.domain.product.entity.Product;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 박스 규격/매칭 목록의 한 행. 미매칭이면 matchedBoxSpec·fillRatePct가 null이다.
 */
public record ProductMatchResponse(
        UUID id,
        String code,
        String name,
        String category,
        String imageUrl,
        Integer widthMm,
        Integer depthMm,
        Integer heightMm,
        MatchedBoxSpec matchedBoxSpec,
        BigDecimal fillRatePct,
        MatchStatus matchStatus,
        String matchSource,
        Integer version
) {

    public record MatchedBoxSpec(UUID id, String code, String name) {

        static MatchedBoxSpec from(BoxSpec boxSpec) {
            return boxSpec == null ? null : new MatchedBoxSpec(boxSpec.getId(), boxSpec.getCode(), boxSpec.getName());
        }
    }

    public static ProductMatchResponse from(Product product) {
        return new ProductMatchResponse(
                product.getId(),
                product.getCode(),
                product.getName(),
                product.getCategory(),
                product.getImageUrl(),
                product.getWidthMm(),
                product.getDepthMm(),
                product.getHeightMm(),
                MatchedBoxSpec.from(product.getMatchedBoxSpec()),
                product.getFillRatePct(),
                product.getMatchStatus(),
                product.getMatchSource(),
                product.getVersion()
        );
    }
}
