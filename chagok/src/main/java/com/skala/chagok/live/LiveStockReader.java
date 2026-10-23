package com.skala.chagok.live;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스트림으로 보낼 현재고와 처리 결과를 읽는다. 확정 직후(AFTER_COMMIT)에도 불리므로, 끝난 트랜잭션에 기대지 않도록
 * 새 읽기 트랜잭션에서 읽는다. 사용자 정보는 읽지 않는다.
 */
@Component
public class LiveStockReader {
    private final NamedParameterJdbcTemplate jdbc;

    LiveStockReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Box(UUID id, String code, String name, Integer onHandQty,
                      Integer innerWidthMm, Integer innerDepthMm, Integer innerHeightMm, Integer version) {
    }

    public record Snapshot(Instant at, List<Box> boxes) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Snapshot snapshot(UUID warehouseId) {
        var boxes = jdbc.query("""
                select id, code, name, on_hand_qty, inner_width_mm, inner_depth_mm, inner_height_mm, version
                from box_spec where warehouse_id = :warehouseId and is_active = true order by code
                """, java.util.Map.of("warehouseId", warehouseId), (rs, i) -> new Box(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                (Integer) rs.getObject("on_hand_qty"), (Integer) rs.getObject("inner_width_mm"),
                (Integer) rs.getObject("inner_depth_mm"), (Integer) rs.getObject("inner_height_mm"), (Integer) rs.getObject("version")));
        return new Snapshot(Instant.now(), boxes);
    }

    /** 처리 결과 한 건. product·box는 연결되지 않았으면 null이다. */
    public record Item(String eventId, Instant observedAt, String status, String reason,
                       Product product, Box box, BigDecimal fillRatePct, Dimensions itemMm) {
    }

    public record Product(UUID id, String code, String name) {
    }

    public record Dimensions(Integer widthMm, Integer depthMm, Integer heightMm) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Item item(String eventId) {
        return jdbc.query("""
                select e.event_id, e.occurred_at, e.status, e.failure_reason, e.fill_rate_pct,
                    p.id as product_id, p.code as product_code, p.name as product_name,
                    b.id, b.code, b.name, b.on_hand_qty, b.inner_width_mm, b.inner_depth_mm, b.inner_height_mm, b.version,
                    coalesce((e.measurement->>'widthMm')::int, p.width_mm) as width_mm,
                    coalesce((e.measurement->>'depthMm')::int, p.depth_mm) as depth_mm,
                    coalesce((e.measurement->>'heightMm')::int, p.height_mm) as height_mm
                from item_event e
                left join product p on p.id=e.product_id and p.warehouse_id=e.warehouse_id
                left join box_spec b on b.id=e.box_spec_id and b.warehouse_id=e.warehouse_id
                where e.event_id=:eventId
                """, java.util.Map.of("eventId", eventId), (rs, i) -> new Item(
                rs.getString("event_id"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("status"),
                rs.getString("failure_reason"), rs.getObject("product_id") == null ? null : new Product(
                        rs.getObject("product_id", UUID.class), rs.getString("product_code"), rs.getString("product_name")),
                rs.getObject("id") == null ? null : new Box(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), (Integer) rs.getObject("on_hand_qty"),
                        (Integer) rs.getObject("inner_width_mm"), (Integer) rs.getObject("inner_depth_mm"),
                        (Integer) rs.getObject("inner_height_mm"), (Integer) rs.getObject("version")),
                rs.getBigDecimal("fill_rate_pct"), new Dimensions((Integer) rs.getObject("width_mm"),
                        (Integer) rs.getObject("depth_mm"), (Integer) rs.getObject("height_mm"))))
                .stream().findFirst().orElse(null);
    }
}
