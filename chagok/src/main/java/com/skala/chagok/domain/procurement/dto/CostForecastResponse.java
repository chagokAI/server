package com.skala.chagok.domain.procurement.dto;

import com.skala.chagok.domain.procurement.entity.CostForecast;
import java.time.Instant;
import java.util.UUID;

public record CostForecastResponse(UUID id, UUID jobId, String targetMonth, Instant dataAsOf, Instant generatedAt,
                                   String currency, boolean vatIncluded, CostForecastResult forecast) {
    public static CostForecastResponse from(CostForecast value) {
        return new CostForecastResponse(value.getId(), value.getJobId(), value.getTargetMonth(), value.getDataAsOf(),
                value.getGeneratedAt(), "KRW", false, value.getResult());
    }
}
