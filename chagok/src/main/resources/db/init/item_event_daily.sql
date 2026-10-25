CREATE TABLE IF NOT EXISTS public.item_event_daily (
    warehouse_id uuid NOT NULL REFERENCES public.warehouse(id),
    day date NOT NULL,
    product_id uuid REFERENCES public.product(id),
    box_spec_id uuid REFERENCES public.box_spec(id),
    status varchar(10) NOT NULL,
    failure_reason varchar(40),
    source varchar(10) NOT NULL DEFAULT 'hist',
    shipments bigint NOT NULL,
    fill_rate_sum numeric(16,2),
    aggregated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_item_event_daily UNIQUE NULLS NOT DISTINCT
        (warehouse_id, day, product_id, box_spec_id, status, failure_reason, source)
);
CREATE INDEX IF NOT EXISTS ix_item_event_daily_wh_day ON public.item_event_daily (warehouse_id, day);

-- 과거 집계와 보관 중인 원본은 중복해서 세지 않는다. 오늘·미래 날짜는 원본을 읽어 새 출고가 바로 보인다.
-- 라이브 시연 입력은 월별 운영 실적과 예측의 학습 자료에서 제외한다.
CREATE OR REPLACE VIEW public.agent_shipment_history AS
SELECT warehouse_id, day, product_id, box_spec_id, status, failure_reason, shipments, fill_rate_sum
FROM public.item_event_daily
WHERE source = 'hist' AND day < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
UNION ALL
SELECT e.warehouse_id, (e.occurred_at AT TIME ZONE 'Asia/Seoul')::date,
       e.product_id, e.box_spec_id, e.status, e.failure_reason, 1::bigint, e.fill_rate_pct
FROM public.item_event e
WHERE e.event_id NOT LIKE 'live-%' AND e.occurred_at <= CURRENT_TIMESTAMP
  AND ((e.occurred_at AT TIME ZONE 'Asia/Seoul')::date >= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul')::date
       OR NOT EXISTS (
           SELECT 1 FROM public.item_event_daily d
           WHERE d.warehouse_id = e.warehouse_id
             AND d.day = (e.occurred_at AT TIME ZONE 'Asia/Seoul')::date AND d.source = 'hist'
       ));
