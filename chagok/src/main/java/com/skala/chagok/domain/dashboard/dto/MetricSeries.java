package com.skala.chagok.domain.dashboard.dto;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

public record MetricSeries(Metric metric, String unit, LocalDate from, LocalDate to, Bucket bucket,
                            String timezone, UUID boxSpecId, List<Point> points) {
    public enum Metric { OBSERVATION_COUNT, SUCCEEDED_COUNT, FAILED_COUNT, PENDING_COUNT, MATCHED_BOX_COUNT, AVG_FILL_RATE_PCT }
    public enum Bucket { day, hour }
    public record Point(OffsetDateTime at, BigDecimal value, long sampleCount) {}
}
