package com.skala.chagok.domain.dashboard.repository;

import com.skala.chagok.domain.dashboard.dto.MetricSeries;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class MetricQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public record Aggregate(Instant at, BigDecimal value, long sampleCount) {}

    public List<Aggregate> aggregate(UUID warehouseId, Instant from, Instant to, String timezone,
                                      MetricSeries.Bucket bucket, MetricSeries.Metric metric, UUID boxSpecId) {
        String expression = switch (metric) {
            case OBSERVATION_COUNT -> "count(*)";
            case SUCCEEDED_COUNT -> "count(*) filter (where status = 'SUCCEEDED')";
            case FAILED_COUNT -> "count(*) filter (where status = 'FAILED')";
            case PENDING_COUNT -> "count(*) filter (where status = 'PENDING')";
            case MATCHED_BOX_COUNT -> "count(*) filter (where status = 'SUCCEEDED' and box_spec_id is not null)";
            case AVG_FILL_RATE_PCT -> "avg(fill_rate_pct) filter (where status = 'SUCCEEDED')";
        };
        String samples = metric == MetricSeries.Metric.AVG_FILL_RATE_PCT
                ? "count(fill_rate_pct) filter (where status = 'SUCCEEDED')" : expression;
        String truncation = bucket == MetricSeries.Bucket.day ? "'day'" : "'hour'";
        String sql = "select date_trunc(" + truncation + ", occurred_at at time zone :timezone) at time zone :timezone as at, "
                + expression + " as metric_value, " + samples + " as sample_count from item_event "
                + "where warehouse_id = :warehouseId and occurred_at >= :from and occurred_at < :to "
                + (boxSpecId == null ? "" : "and box_spec_id = :boxSpecId ") + "group by 1 order by 1";
        Map<String, Object> params = new HashMap<>();
        params.put("timezone", timezone); params.put("warehouseId", warehouseId);
        params.put("from", Timestamp.from(from)); params.put("to", Timestamp.from(to)); params.put("boxSpecId", boxSpecId);
        return jdbc.query(sql, params, (rs, row) -> new Aggregate(rs.getTimestamp("at").toInstant(),
                rs.getBigDecimal("metric_value"), rs.getLong("sample_count")));
    }
}
