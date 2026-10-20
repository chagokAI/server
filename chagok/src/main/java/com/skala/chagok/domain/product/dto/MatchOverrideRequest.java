package com.skala.chagok.domain.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record MatchOverrideRequest(
        @NotNull @jakarta.validation.constraints.Min(0) Integer expectedVersion,
        @NotNull UUID boxSpecId,
        @NotBlank @Size(max = 200) String matchOverrideReason
) {
}
