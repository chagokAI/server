package com.skala.chagok.domain.job.dto;

import jakarta.validation.constraints.*;

public record JobRetryRequest(@NotNull @Min(0) Integer expectedVersion) {}
