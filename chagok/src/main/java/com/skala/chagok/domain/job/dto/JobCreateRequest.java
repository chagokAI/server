package com.skala.chagok.domain.job.dto;

import com.skala.chagok.domain.job.entity.AnalysisJob;
import jakarta.validation.constraints.*;

public record JobCreateRequest(@NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String targetMonth,
                               @NotNull AnalysisJob.Type type) {}
