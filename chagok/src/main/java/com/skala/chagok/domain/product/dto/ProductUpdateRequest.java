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
        @NotNull @jakarta.validation.constraints.Min(0) Integer expectedVersion,
        @Size(min = 1, max = 100) String name,
        UUID categoryId,
        @Positive Integer widthMm,
        @Positive Integer depthMm,
        @Positive Integer heightMm,
        @Size(min = 1, max = 500) String imageUrl,
        UUID boxSpecId,
        @Size(max = 200) String matchOverrideReason,
        // 입고 상품 일부(패션 상품 등)는 원천 식별자가 영문·숫자 혼합(예: NCW2S06390)이고 DL도 그 값을 바코드로 돌려준다.
        // 숫자만 받으면 이런 상품을 화면에서 수정할 때 400이 나므로 영문·숫자 1~30자를 허용한다
        @jakarta.validation.constraints.Pattern(regexp = "[0-9A-Za-z]{1,30}") String barcode,
        @jakarta.validation.constraints.PositiveOrZero Integer weightG
) {
}
