package com.skala.chagok.domain.box.dto;

import com.skala.chagok.domain.box.entity.BoxMaterial;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 규격 추가. 크기 분류(S/M/L)는 내경 부피로 서버가 정하므로 받지 않는다.
 * material은 BoxMaterial 코드(A, B, E, AB, BA, BB)만 허용한다.
 */
public record BoxSpecCreateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 50) String name,
        @NotNull @Positive Integer innerWidthMm,
        @NotNull @Positive Integer innerDepthMm,
        @NotNull @Positive Integer innerHeightMm,
        @NotNull BoxMaterial material,
        UUID supplierId,
        @NotNull @PositiveOrZero Integer unitPriceKrw,
        @NotNull Boolean isCustom,
        @Size(max = 200) String description,
        Boolean isActive,
        @PositiveOrZero Integer initialOnHandQty,
        @PositiveOrZero Integer safetyQty,
        @PositiveOrZero Integer targetQty
) {
}
