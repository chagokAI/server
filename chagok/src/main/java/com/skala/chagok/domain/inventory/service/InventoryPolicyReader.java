package com.skala.chagok.domain.inventory.service;

import com.skala.chagok.domain.box.entity.BoxSpec;
import com.skala.chagok.global.config.ChagokProperties;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class InventoryPolicyReader {
    private final NamedParameterJdbcTemplate jdbc;
    private final ChagokProperties properties;
    private final Clock clock;
    // 운영 계획용 가정이다. DB 공급사의 lead_time_days는 변경하거나 대체 저장하지 않는다.
    @Value("${chagok.inventory.planning-lead-time-days:5}")
    private int planningLeadDays = 5;

    public Map<UUID, InventoryPolicyProjection> read(UUID warehouseId, List<BoxSpec> specs, InventoryUsage usage) {
        if (specs.isEmpty()) return Map.of();
        LocalDate today = LocalDate.now(clock.withZone(properties.timezone()));
        Map<UUID, LocalDate> deliveries = new HashMap<>();
        jdbc.query("""
                select i.box_spec_id, min(o.requested_delivery_date) as delivery_date
                from purchase_order o join purchase_order_item i on i.purchase_order_id = o.id
                left join (
                    select purchase_order_id, box_spec_id, sum(delta_qty) as received
                    from inventory_movement where warehouse_id = :wh and type = 'RECEIPT'
                    group by purchase_order_id, box_spec_id
                ) r on r.purchase_order_id = o.id and r.box_spec_id = i.box_spec_id
                where o.warehouse_id = :wh and o.status in ('APPROVED', 'IN_TRANSIT')
                    and o.requested_delivery_date >= :today and i.qty > coalesce(r.received, 0)
                group by i.box_spec_id
                """, Map.of("wh", warehouseId, "today", today), rs -> {
            deliveries.put(rs.getObject("box_spec_id", UUID.class), rs.getDate("delivery_date").toLocalDate());
        });
        Map<UUID, InventoryPolicyProjection> result = new HashMap<>();
        for (var box : specs) result.put(box.getId(), InventoryPolicyProjection.calculate(box, usage, today,
                deliveries.get(box.getId()), planningLeadDays, properties.inventory().cautionBufferDays()));
        return result;
    }
}
