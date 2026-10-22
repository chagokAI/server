CREATE EXTENSION IF NOT EXISTS vector;

-- 인계 덤프의 DL 표본. 새 환경에도 같은 조회 계약을 제공한다(기존 데이터는 보존).
CREATE TABLE IF NOT EXISTS dl_measurement_sample (
    event_id varchar(64) NOT NULL, run smallint NOT NULL, bundle_id varchar(64) NOT NULL,
    expected_barcode varchar(30) NOT NULL, dl_status varchar(10) NOT NULL, dl_barcode varchar(30),
    size_type varchar(10), width_cm numeric(6,1), depth_cm numeric(6,1), height_cm numeric(6,1),
    volume_cm3 numeric(10,1), latency_ms integer, measured_at timestamptz NOT NULL, failure_reason varchar(30)
);
CREATE INDEX IF NOT EXISTS ix_dl_measurement_event ON dl_measurement_sample(event_id, measured_at DESC, run DESC);
