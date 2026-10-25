CREATE EXTENSION IF NOT EXISTS vector;

-- 인계 덤프의 DL 표본. 새 환경에도 같은 조회 계약을 제공한다(기존 데이터는 보존).
CREATE TABLE IF NOT EXISTS dl_measurement_sample (
    event_id varchar(64) NOT NULL, run smallint NOT NULL, bundle_id varchar(64) NOT NULL,
    expected_barcode varchar(30) NOT NULL, dl_status varchar(10) NOT NULL, dl_barcode varchar(30),
    size_type varchar(10), width_cm numeric(6,1), depth_cm numeric(6,1), height_cm numeric(6,1),
    volume_cm3 numeric(10,1), latency_ms integer, measured_at timestamptz NOT NULL, failure_reason varchar(30)
);
CREATE INDEX IF NOT EXISTS ix_dl_measurement_event ON dl_measurement_sample(event_id, measured_at DESC, run DESC);
-- 표본이 어느 센터 것인지 직접 기록한다. 예전에는 event_id 로 출고 원본(item_event)을 찾아 센터를 알아냈는데,
-- 원본을 정리·이관하면 표본이 끊겼다. 비어 있는 예전 표본은 실시간 재생이 원본 연결로 찾는다
ALTER TABLE dl_measurement_sample ADD COLUMN IF NOT EXISTS warehouse_id uuid;
CREATE INDEX IF NOT EXISTS ix_dl_measurement_warehouse ON dl_measurement_sample(warehouse_id, measured_at, run, event_id);
