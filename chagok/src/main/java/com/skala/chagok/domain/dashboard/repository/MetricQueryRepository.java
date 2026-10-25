package com.skala.chagok.domain.dashboard.repository;

import com.skala.chagok.domain.dashboard.dto.MetricSeries;
import com.skala.chagok.domain.event.repository.ShipmentHistorySql;
import java.math.BigDecimal;
import java.time.*;
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
        boolean daily = bucket == MetricSeries.Bucket.day;
        String count = daily ? "sum(shipments)" : "count(*)";
        String expression = switch (metric) {
            case OBSERVATION_COUNT -> count;
            case SUCCEEDED_COUNT -> count + " filter (where status = 'SUCCEEDED')";
            case FAILED_COUNT -> count + " filter (where status = 'FAILED')";
            case PENDING_COUNT -> count + " filter (where status = 'PENDING')";
            case MATCHED_BOX_COUNT -> count + " filter (where status = 'SUCCEEDED' and box_spec_id is not null)";
            case AVG_FILL_RATE_PCT -> daily
                    ? "sum(fill_rate_sum) filter (where status = 'SUCCEEDED') / nullif(sum(fill_samples) filter (where status = 'SUCCEEDED'), 0)"
                    : "avg(fill_rate_pct) filter (where status = 'SUCCEEDED')";
        };
        if (daily && metric != MetricSeries.Metric.AVG_FILL_RATE_PCT) expression = "coalesce(" + expression + ", 0)";
        String samples = metric == MetricSeries.Metric.AVG_FILL_RATE_PCT
                ? (daily ? "sum(fill_samples)" : "count(fill_rate_pct)") + " filter (where status = 'SUCCEEDED')" : expression;
        String truncation = bucket == MetricSeries.Bucket.day ? "'day'" : "'hour'";
        String sql = (daily ? ShipmentHistorySql.CTE : "")
                + "select date_trunc(" + truncation + ", occurred_at at time zone :timezone) at time zone :timezone as at, "
                + expression + " as metric_value, " + samples + " as sample_count from " + (daily ? "shipment_history" : "item_event")
                + " where " + (daily ? "1=1 " : "warehouse_id = :warehouseId and occurred_at >= :from and occurred_at < :to and occurred_at <= :asOf ")
                + (boxSpecId == null ? "" : "and box_spec_id = :boxSpecId ") + "group by 1 order by 1";
        Map<String, Object> params = ShipmentHistorySql.parameters(warehouseId, from, to, ZoneId.of(timezone));
        params.put("boxSpecId", boxSpecId);
        return jdbc.query(sql, params, (rs, row) -> new Aggregate(rs.getTimestamp("at").toInstant(),
                rs.getBigDecimal("metric_value"), rs.getLong("sample_count")));
    }
}
