package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxMaterial;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 전달된 필드만 수정한다. 내경을 바꾸면 크기 분류(S/M/L)도 서버가 다시 정한다.
 */
public record BoxSpecUpdateRequest(
        @NotNull @jakarta.validation.constraints.Min(0) Integer expectedVersion,
        @Size(min = 1, max = 20) String code,
        @Size(min = 1, max = 50) String name,
        @Positive Integer innerWidthMm,
        @Positive Integer innerDepthMm,
        @Positive Integer innerHeightMm,
        BoxMaterial material,
        UUID supplierId,
        @PositiveOrZero Integer unitPriceKrw,
        Boolean isCustom,
        Boolean isActive,
        @Size(max = 200) String description
) {
}
