package com.skala.chagok.domain.product.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MatchOverrideRequest(
        @NotNull Integer expectedVersion,
        @NotNull UUID boxSpecId
) {
}
