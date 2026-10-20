package com.skala.chagok.domain.product.dto;

import com.skala.chagok.domain.category.dto.CategoryResponse;
import com.skala.chagok.domain.product.entity.MatchStatus;
import com.skala.chagok.domain.product.entity.Product;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductDetailResponse(
        UUID id,
        String code,
        String barcode,
        Integer weightG,
        String name,
        CategoryResponse category,
        String imageUrl,
        Integer widthMm,
        Integer depthMm,
        Integer heightMm,
        ProductMatchResponse.MatchedBoxSpec matchedBoxSpec,
        BigDecimal fillRatePct,
        MatchStatus matchStatus,
        String matchSource,
        String matchOverrideReason,
        Boolean isActive,
        Integer version,
        java.util.List<MatchCandidateResponse> matchCandidates
) {

    public static ProductDetailResponse from(Product product) {
        return from(product, java.util.List.of());
    }

    public static ProductDetailResponse from(Product product, java.util.List<MatchCandidateResponse> candidates) {
        return new ProductDetailResponse(
                product.getId(),
                product.getCode(),
                product.getBarcode(),
                product.getWeightG(),
                product.getName(),
                CategoryResponse.from(product.getCategory()),
                product.getImageUrl(),
                product.getWidthMm(),
                product.getDepthMm(),
                product.getHeightMm(),
                ProductMatchResponse.MatchedBoxSpec.from(product.getMatchedBoxSpec()),
                product.getFillRatePct(),
                product.getMatchStatus(),
                product.getMatchSource(),
                product.getMatchOverrideReason(),
                product.getIsActive(),
                product.getVersion(),
                candidates
        );
    }
}
