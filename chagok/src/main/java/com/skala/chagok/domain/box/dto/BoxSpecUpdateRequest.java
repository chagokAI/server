package com.skala.chagok.domain.box.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 전달된 필드만 수정한다.
 */
public record BoxSpecUpdateRequest(
        @NotNull @jakarta.validation.constraints.Min(0) Integer expectedVersion,
        @Size(min = 1, max = 20) String code,
        @Size(min = 1, max = 50) String name,
        @Pattern(regexp = "^[A-Z]$", message = "영문 대문자 한 글자여야 합니다.") String sizeClass,
        @Positive Integer innerWidthMm,
        @Positive Integer innerDepthMm,
        @Positive Integer innerHeightMm,
        @Size(max = 50) String material,
        UUID supplierId,
        @PositiveOrZero Integer unitPriceKrw,
        Boolean isCustom,
        Boolean isActive,
        @Size(max = 200) String description
) {
}
