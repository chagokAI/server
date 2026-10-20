package com.skala.chagok.domain.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 전달된 필드만 수정한다. 문자열은 빈 값으로 지울 수 없다.
 */
public record ProductUpdateRequest(
        @NotNull Integer expectedVersion,
        @Size(min = 1, max = 30) String code,
        @Size(min = 1, max = 100) String name,
        @Size(min = 1, max = 50) String category,
        @Positive Integer widthMm,
        @Positive Integer depthMm,
        @Positive Integer heightMm,
        @Size(min = 1, max = 500) String imageUrl
) {
}
