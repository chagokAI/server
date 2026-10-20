package com.skala.chagok.domain.purchase.repository;

import com.skala.chagok.global.web.LikePatterns;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class PurchaseHistorySummaryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public record StatusTotals(String status, long count, BigDecimal amountKrw, BigDecimal leadDays, long leadSamples, long incompleteAmounts) {}

    public List<StatusTotals> summarize(UUID warehouseId, Instant from, Instant until, String keyword) {
        boolean searching = keyword != null && !keyword.isBlank();
        String sql = """
                select po.status, count(*) as order_count,
                       coalesce(sum(case when po.status <> 'REJECTED' then lines.amount else 0 end), 0) as amount,
                       coalesce(sum(case when po.received_at >= po.approved_at
                           then extract(epoch from (po.received_at - po.approved_at)) / 86400.0 else 0 end), 0) as lead_days,
                       count(*) filter (where po.received_at >= po.approved_at) as lead_samples,
                       count(*) filter (where po.status <> 'REJECTED' and lines.amount is null) as incomplete_amounts
                from purchase_order po left join supplier s on s.id = po.supplier_id
                left join (select purchase_order_id, case when count(*) = count(qty) and count(*) = count(unit_price_krw)
                           then sum(cast(qty as bigint) * unit_price_krw) else null end as amount
                           from purchase_order_item group by purchase_order_id) lines on lines.purchase_order_id = po.id
                where po.warehouse_id = :warehouseId and po.created_at >= :from and po.created_at < :until
                """ + (searching ? "and (lower(po.po_no) like :keyword escape '\\' or lower(s.name) like :keyword escape '\\') " : "")
                + "group by po.status";
        Map<String, Object> params = new HashMap<>();
        params.put("warehouseId", warehouseId); params.put("from", Timestamp.from(from)); params.put("until", Timestamp.from(until));
        if (searching) params.put("keyword", LikePatterns.containsIgnoreCase(keyword));
        return jdbc.query(sql, params, (rs, row) -> new StatusTotals(rs.getString("status"), rs.getLong("order_count"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("lead_days"), rs.getLong("lead_samples"), rs.getLong("incomplete_amounts")));
    }
}
