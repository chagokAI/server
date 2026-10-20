package com.skala.chagok.domain.box.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record BoxSpecCreateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 50) String name,
        @NotNull @Pattern(regexp = "^[A-Z]$", message = "영문 대문자 한 글자여야 합니다.") String sizeClass,
        @NotNull @Positive Integer innerWidthMm,
        @NotNull @Positive Integer innerDepthMm,
        @NotNull @Positive Integer innerHeightMm,
        @Size(max = 50) String material,
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
