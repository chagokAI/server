package com.skala.chagok.domain.agent.repository;

import com.skala.chagok.domain.agent.dto.AgentDataDtos.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** 에이전트용 조회·제안 저장. 테이블은 JPA 엔티티와 같은 것을 쓴다. */
@Repository @RequiredArgsConstructor
public class AgentDataRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final NamedParameterJdbcTemplate jdbc;

    public List<WarehouseItem> warehouses() {
        return jdbc.query("select id, name, address from warehouse order by name", Map.of(),
                (rs, i) -> new WarehouseItem(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address")));
    }

    public List<BoxSpecItem> boxSpecs(UUID warehouseId, boolean activeOnly) {
        String sql = "select b.id, b.code, b.name, b.description, b.size_class, b.inner_width_mm, b.inner_depth_mm, "
                + "b.inner_height_mm, b.material, b.unit_price_krw, b.is_custom, b.is_active, b.on_hand_qty, b.safety_qty, "
                + "b.safety_days, b.stock_updated_at, s.name as supplier, s.lead_time_days "
                + "from box_spec b left join supplier s on s.id = b.supplier_id where b.warehouse_id = :wh "
                + (activeOnly ? "and coalesce(b.is_active, true) " : "") + "order by b.code";
        return jdbc.query(sql, Map.of("wh", warehouseId), (rs, i) -> new BoxSpecItem(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("description"), rs.getString("size_class"),
                (Integer) rs.getObject("inner_width_mm"), (Integer) rs.getObject("inner_depth_mm"),
                (Integer) rs.getObject("inner_height_mm"), rs.getString("material"), (Integer) rs.getObject("unit_price_krw"),
                rs.getBoolean("is_custom"), !Boolean.FALSE.equals(rs.getObject("is_active")),
                (Integer) rs.getObject("on_hand_qty"), (Integer) rs.getObject("safety_qty"),
                (Integer) rs.getObject("safety_days"), instant(rs.getTimestamp("stock_updated_at")),
                rs.getString("supplier"), rs.getObject("lead_time_days") == null ? null : rs.getInt("lead_time_days")));
    }

    public EventStats eventStats(UUID warehouseId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Instant[] last = {null};
        jdbc.query("select status, count(*) as n, max(occurred_at) as last from item_event where warehouse_id = :wh "
                + "group by status order by status", Map.of("wh", warehouseId), rs -> {
            counts.put(String.valueOf(rs.getString("status")), rs.getLong("n"));
            Instant at = instant(rs.getTimestamp("last"));
            if (at != null && (last[0] == null || at.isAfter(last[0]))) last[0] = at;
        });
        return new EventStats(counts, last[0], counts.getOrDefault("PENDING", 0L));
    }

    public List<ProductShipment> productShipments(UUID warehouseId, Instant from, Instant to, List<String> statuses) {
        var params = new HashMap<String, Object>(Map.of("wh", warehouseId, "from", Timestamp.from(from),
                "to", Timestamp.from(to)));
        String filter = statusFilter(statuses, params);
        String sql = "select p.id, p.code, p.name, c.name as category, p.width_mm, p.depth_mm, p.height_mm, "
                + "bs.code as matched_box, p.fill_rate_pct, coalesce(e.cnt, 0) as shipments "
                + "from product p left join box_spec bs on bs.id = p.matched_box_spec_id "
                + "left join category c on c.id = p.category_id "
                + "left join (select product_id, count(*) as cnt from item_event where warehouse_id = :wh "
                + "and occurred_at >= :from and occurred_at < :to" + filter + " group by product_id) e on e.product_id = p.id "
                + "where p.warehouse_id = :wh and coalesce(p.is_active, true) order by p.code";
        return jdbc.query(sql, params, (rs, i) -> new ProductShipment(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("category"), (Integer) rs.getObject("width_mm"),
                (Integer) rs.getObject("depth_mm"), (Integer) rs.getObject("height_mm"), rs.getString("matched_box"),
                rs.getBigDecimal("fill_rate_pct") == null ? null : rs.getBigDecimal("fill_rate_pct").doubleValue(),
                rs.getLong("shipments")));
    }

    public List<MonthlyShipment> monthlyShipments(UUID warehouseId, Instant from, Instant to, List<String> statuses,
                                                  ZoneId zone) {
        var params = new HashMap<String, Object>(Map.of("wh", warehouseId, "from", Timestamp.from(from),
                "to", Timestamp.from(to), "tz", zone.getId()));
        String sql = "select to_char(date_trunc('month', occurred_at at time zone :tz), 'YYYY-MM') as month, count(*) as n "
                + "from item_event where warehouse_id = :wh and occurred_at >= :from and occurred_at < :to"
                + statusFilter(statuses, params) + " group by 1 order by 1";
        return jdbc.query(sql, params, (rs, i) -> new MonthlyShipment(rs.getString("month"), rs.getLong("n")));
    }

    public List<PurchaseOrderItem> purchaseOrders(UUID warehouseId, LocalDate from, LocalDate to, ZoneId zone) {
        String sql = "select po.id, po.po_no, po.status, po.created_at, po.requested_delivery_date, "
                + "po.proposal_id is not null as from_proposal, pp.target_month, s.name as supplier, "
                + "coalesce(sum(i.qty::bigint * i.unit_price_krw), 0) as amount from purchase_order po "
                + "left join procurement_proposal pp on pp.id = po.proposal_id left join supplier s on s.id = po.supplier_id "
                + "left join purchase_order_item i on i.purchase_order_id = po.id where po.warehouse_id = :wh "
                + "and (po.created_at at time zone :tz)::date between :from and :to "
                + "group by po.id, pp.target_month, s.name order by po.created_at";
        return jdbc.query(sql, Map.of("wh", warehouseId, "from", from, "to", to, "tz", zone.getId()),
                (rs, i) -> new PurchaseOrderItem(rs.getObject("id", UUID.class), rs.getString("po_no"), rs.getString("status"),
                        instant(rs.getTimestamp("created_at")),
                        rs.getDate("requested_delivery_date") == null ? null : rs.getDate("requested_delivery_date").toLocalDate(),
                        rs.getBoolean("from_proposal"), trim(rs.getString("target_month")), rs.getString("supplier"),
                        rs.getLong("amount")));
    }

    public List<ProposalItem> proposals(UUID warehouseId, int limit) {
        String sql = "select id, target_month, generated_at, summary->>'jobId' as job_id, summary->>'totalAmount' as total, "
                + "(summary->'newSpecs')::text as new_specs, (summary->'forecast')::text as forecast "
                + "from procurement_proposal where warehouse_id = :wh order by generated_at desc nulls last limit :n";
        return jdbc.query(sql, Map.of("wh", warehouseId, "n", limit), (rs, i) -> new ProposalItem(
                rs.getObject("id", UUID.class), trim(rs.getString("target_month")), instant(rs.getTimestamp("generated_at")),
                rs.getString("job_id"), rs.getString("total") == null ? null : (long) Double.parseDouble(rs.getString("total")),
                json(rs.getString("new_specs")), json(rs.getString("forecast"))));
    }

    public long countBoxSpecs(UUID warehouseId, Collection<UUID> ids) {
        if (ids.isEmpty()) return 0;
        Long n = jdbc.queryForObject("select count(*) from box_spec where warehouse_id = :wh and id in (:ids)",
                Map.of("wh", warehouseId, "ids", ids), Long.class);
        return n == null ? 0 : n;
    }

    public boolean warehouseExists(UUID warehouseId) {
        Long n = jdbc.queryForObject("select count(*) from warehouse where id = :wh", Map.of("wh", warehouseId), Long.class);
        return n != null && n > 0;
    }

    /** 다른 센터의 제안 id 로는 덮어쓰지 않는다(호출자가 먼저 확인). */
    public UUID proposalWarehouse(UUID proposalId) {
        var rows = jdbc.query("select warehouse_id from procurement_proposal where id = :id", Map.of("id", proposalId),
                (rs, i) -> rs.getObject("warehouse_id", UUID.class));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void upsertProposal(UUID proposalId, UUID warehouseId, ProposalSaveRequest req) {
        var params = new HashMap<String, Object>();
        params.put("id", proposalId); params.put("wh", warehouseId); params.put("tm", req.targetMonth());
        params.put("summary", JSON.writeValueAsString(req.summary()));
        params.put("at", Timestamp.from(req.generatedAt() == null ? Instant.now() : req.generatedAt()));
        params.put("asof", req.dataAsOf() == null ? null : Timestamp.from(req.dataAsOf()));
        jdbc.update("insert into procurement_proposal (id, warehouse_id, target_month, summary, generated_at, data_as_of) "
                + "values (:id, :wh, :tm, cast(:summary as jsonb), :at, :asof) on conflict (id) do update set "
                + "warehouse_id = excluded.warehouse_id, target_month = excluded.target_month, summary = excluded.summary, "
                + "generated_at = excluded.generated_at, data_as_of = excluded.data_as_of", params);
        jdbc.update("delete from procurement_proposal_item where proposal_id = :id", Map.of("id", proposalId));
        for (ProposalLine line : req.items()) {
            var p = new HashMap<String, Object>();
            p.put("id", proposalId); p.put("box", line.boxSpecId()); p.put("onHand", line.onHandQty());
            p.put("safety", line.safetyQty()); p.put("usage", line.forecastUsageQty()); p.put("qty", line.proposedQty());
            p.put("price", line.unitPriceKrw());
            p.put("snapshot", line.boxSnapshot() == null ? null : JSON.writeValueAsString(line.boxSnapshot()));
            jdbc.update("insert into procurement_proposal_item (proposal_id, box_spec_id, on_hand_qty, safety_qty, "
                    + "forecast_usage_qty, proposed_qty, unit_price_krw, box_snapshot) values (:id, :box, :onHand, :safety, "
                    + ":usage, :qty, :price, cast(:snapshot as jsonb))", p);
        }
    }

    private static String statusFilter(List<String> statuses, Map<String, Object> params) {
        if (statuses == null || statuses.isEmpty()) return "";
        params.put("statuses", statuses);
        return " and status in (:statuses)";
    }

    private static Instant instant(Timestamp ts) { return ts == null ? null : ts.toInstant(); }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static Object json(String text) { return text == null ? null : JSON.readValue(text, Object.class); }
}
