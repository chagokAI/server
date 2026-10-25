package com.skala.chagok.domain.event.repository;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 일별 실적 + 집계가 없는 날/오늘의 원본. 같은 센터·날짜·출처의 보관 원본은 중복하지 않는다. */
public final class ShipmentHistorySql {
    private ShipmentHistorySql() {}

    public static final String CTE = """
            with shipment_history as (
                select d.warehouse_id, d.day, d.product_id, d.box_spec_id, d.status, d.failure_reason, d.source,
                    d.shipments, d.fill_rate_sum,
                    case when d.fill_rate_sum is null then 0 else d.shipments end as fill_samples,
                    cast(d.day as timestamp) at time zone :timezone as occurred_at, null as measurement
                from item_event_daily d
                where d.warehouse_id = :warehouseId and d.day >= :fromDay and d.day < :toDay
                    and d.day < :today and d.source in ('hist', 'live')
                union all
                select e.warehouse_id, cast(e.occurred_at at time zone :timezone as date), e.product_id, e.box_spec_id,
                    e.status, e.failure_reason, case when e.event_id like 'live-%' then 'live' else 'hist' end,
                    1, e.fill_rate_pct, case when e.fill_rate_pct is null then 0 else 1 end,
                    e.occurred_at, e.measurement
                from item_event e
                where e.warehouse_id = :warehouseId and e.occurred_at >= :from and e.occurred_at < :to
                    and e.occurred_at <= :asOf
                    and not exists (
                        select 1 from item_event_daily d
                        where d.warehouse_id = e.warehouse_id
                            and d.day = cast(e.occurred_at at time zone :timezone as date)
                            and d.day >= :fromDay and d.day < :toDay and d.day < :today
                            and d.source = case when e.event_id like 'live-%' then 'live' else 'hist' end
                    )
            )
            """;

    public static Map<String, Object> parameters(UUID warehouseId, Instant from, Instant to, ZoneId zone) {
        Instant now = Instant.now();
        Map<String, Object> params = new HashMap<>();
        params.put("warehouseId", warehouseId);
        params.put("from", Timestamp.from(from));
        params.put("to", Timestamp.from(to));
        params.put("fromDay", from.atZone(zone).toLocalDate());
        params.put("toDay", to.atZone(zone).toLocalDate());
        params.put("today", now.atZone(zone).toLocalDate());
        params.put("asOf", Timestamp.from(now));
        params.put("timezone", zone.getId());
        return params;
    }
}
