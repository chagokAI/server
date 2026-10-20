package com.skala.chagok.domain.product.dto;

import com.skala.chagok.domain.product.entity.MatchStatus;
import com.skala.chagok.domain.product.entity.Product;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductDetailResponse(
        UUID id,
        String code,
        String name,
        String category,
        String imageUrl,
        Integer widthMm,
        Integer depthMm,
        Integer heightMm,
        ProductMatchResponse.MatchedBoxSpec matchedBoxSpec,
        BigDecimal fillRatePct,
        MatchStatus matchStatus,
        String matchSource,
        Boolean isActive,
        Integer version
) {

    public static ProductDetailResponse from(Product product) {
        return new ProductDetailResponse(
                product.getId(),
                product.getCode(),
                product.getName(),
                product.getCategory(),
                product.getImageUrl(),
                product.getWidthMm(),
                product.getDepthMm(),
                product.getHeightMm(),
                ProductMatchResponse.MatchedBoxSpec.from(product.getMatchedBoxSpec()),
                product.getFillRatePct(),
                product.getMatchStatus(),
                product.getMatchSource(),
                product.getIsActive(),
                product.getVersion()
        );
    }
}
