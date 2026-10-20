package com.skala.chagok.domain.product.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 상품 수정 모달의 [저장하기]. 전달된 필드만 수정한다.
 * 상품코드는 수정할 수 없다.
 * boxSpecId가 현재 매칭 박스와 다르면 직접 지정으로 보고 matchOverrideReason이 필수다.
 */
public record ProductUpdateRequest(
        @NotNull Integer expectedVersion,
        @Size(min = 1, max = 100) String name,
        UUID categoryId,
        @Positive Integer widthMm,
        @Positive Integer depthMm,
        @Positive Integer heightMm,
        @Size(min = 1, max = 500) String imageUrl,
        UUID boxSpecId,
        @Size(max = 200) String matchOverrideReason
) {
}
