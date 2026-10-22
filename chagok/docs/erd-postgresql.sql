-- 차곡 ERD DDL — 2026-10-21 현재 작업 트리 기준
-- 기준: src/main/java/com/skala/chagok/domain/**/entity 의 JPA 엔티티 15개
-- DB: PostgreSQL 17 + pgvector. Hibernate 7.4.5.Final의 매핑으로 생성했다.
-- 새 스키마 생성/ERD 가져오기용이며, 기존 DB를 변경하는 마이그레이션이 아니다.
-- PK, 실제 FK 24개, UNIQUE 3개, 일반 인덱스 7개와 enum CHECK를 반영했다.
-- UUID, @Version, @CreationTimestamp 값은 Hibernate가 생성하므로 DB DEFAULT를 추가하지 않았다.
-- NULL 허용 여부는 Hibernate 생성 결과를 따른다. 서비스의 입력 검증은 DB 제약으로 추가하지 않았다.
-- FK에 ON DELETE/ON UPDATE CASCADE는 지정되어 있지 않다.
-- ERD 도구에서는 PostgreSQL 방언을 선택한다. vector(384)/확장 명령 지원 여부는 도구별로 다르다.

CREATE EXTENSION IF NOT EXISTS vector;

-- 아래 UUID 컬럼은 코드에서 ID로만 보관하며 실제 DB FK 제약은 없다.
-- 논리 관계를 ERD에 표시하려면 이 목록을 참고해 별도로 연결한다.
-- inventory_movement.warehouse_id     -> warehouse.id
-- inventory_movement.box_spec_id      -> box_spec.id
-- inventory_movement.purchase_order_id -> purchase_order.id
-- analysis_job.proposal_id            -> procurement_proposal.id
-- analysis_job.cost_forecast_id       -> cost_forecast.id
-- analysis_job.retry_of_job_id        -> analysis_job.id
-- analysis_job.retry_job_id           -> analysis_job.id
-- procurement_proposal.job_id         -> analysis_job.id
-- cost_forecast.job_id                -> analysis_job.id

-- 테이블
create table analysis_job (
    version integer not null,
    target_month varchar(7) not null,
    completed_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    data_as_of timestamp(6) with time zone,
    started_at timestamp(6) with time zone,
    cost_forecast_id uuid,
    id uuid not null,
    proposal_id uuid,
    retry_job_id uuid,
    retry_of_job_id uuid,
    warehouse_id uuid not null,
    status varchar(20) not null check ((status in ('PENDING','RUNNING','SUCCEEDED','FAILED','PUBLISH_FAILED','TIMED_OUT'))),
    trigger_source varchar(20) check ((trigger_source in ('MANUAL','SCHEDULED','RETRY'))),
    job_type varchar(40) not null check ((job_type in ('PROCUREMENT_AND_COST'))),
    failure_code varchar(80),
    active_key varchar(100) unique,
    failure_message varchar(500),
    primary key (id)
);

create table app_user (
    id uuid not null,
    warehouse_id uuid,
    role varchar(20),
    employee_no varchar(30),
    department varchar(50),
    job_title varchar(50),
    name varchar(50),
    password_hash varchar(255),
    primary key (id)
);

create table box_spec (
    alert_enabled boolean,
    days_to_stockout integer,
    inner_depth_mm integer,
    inner_height_mm integer,
    inner_width_mm integer,
    is_active boolean,
    is_custom boolean,
    on_hand_qty integer,
    safety_days integer,
    safety_qty integer,
    size_class char(1),
    target_qty integer,
    unit_price_krw integer,
    version integer not null,
    forecast_updated_at timestamptz,
    stock_updated_at timestamptz,
    id uuid not null,
    supplier_id uuid,
    warehouse_id uuid,
    code varchar(20),
    material varchar(50),
    name varchar(50),
    description varchar(200),
    primary key (id)
);

create table category (
    id uuid not null,
    warehouse_id uuid,
    name varchar(50),
    primary key (id)
);

create table cost_forecast (
    target_month varchar(7) not null,
    data_as_of timestamp(6) with time zone,
    generated_at timestamp(6) with time zone not null,
    id uuid not null,
    job_id uuid,
    warehouse_id uuid not null,
    result jsonb not null,
    primary key (id)
);

create table inventory_movement (
    after_qty integer not null,
    before_qty integer,
    occurred_on date not null,
    created_at timestamp(6) with time zone not null,
    delta_qty bigint,
    box_spec_id uuid not null,
    id uuid not null,
    purchase_order_id uuid,
    request_id uuid not null,
    warehouse_id uuid not null,
    type varchar(20) not null,
    memo varchar(500),
    primary key (id),
    constraint uq_inventory_request unique (warehouse_id, request_id)
);

create table item_event (
    fill_rate_pct numeric(5,2),
    occurred_at timestamptz not null,
    status varchar(10),
    box_spec_id uuid,
    product_id uuid,
    warehouse_id uuid,
    event_id varchar(64) not null,
    placement jsonb,
    primary key (occurred_at, event_id)
);

create table notification (
    created_at timestamptz not null,
    read_at timestamptz,
    id uuid not null,
    recipient_id uuid,
    category varchar(20),
    link varchar(200),
    title varchar(200),
    message varchar(500),
    primary key (id)
);

create table procurement_proposal (
    target_month char(7),
    data_as_of timestamptz,
    generated_at timestamptz not null,
    id uuid not null,
    job_id uuid,
    warehouse_id uuid,
    summary jsonb,
    primary key (id)
);

create table procurement_proposal_item (
    forecast_usage_qty integer,
    on_hand_qty integer,
    proposed_qty integer,
    safety_qty integer,
    unit_price_krw integer,
    box_spec_id uuid not null,
    proposal_id uuid not null,
    box_snapshot jsonb,
    primary key (box_spec_id, proposal_id)
);

create table product (
    depth_mm integer,
    fill_rate_pct numeric(5,2),
    height_mm integer,
    is_active boolean,
    version integer not null,
    weight_g integer,
    width_mm integer,
    match_source varchar(10),
    category_id uuid,
    id uuid not null,
    matched_box_spec_id uuid,
    warehouse_id uuid,
    match_status varchar(20) check ((match_status in ('MATCHED','REVIEW','UNMATCHED'))),
    barcode varchar(30),
    code varchar(30),
    name varchar(100),
    match_override_reason varchar(200),
    image_url varchar(500),
    embedding vector(384),
    primary key (id),
    constraint uq_product_barcode unique (warehouse_id, barcode)
);

create table purchase_order (
    requested_delivery_date date,
    version integer not null,
    approved_at timestamptz,
    created_at timestamptz not null,
    received_at timestamptz,
    updated_at timestamptz,
    approver_id uuid,
    id uuid not null,
    proposal_id uuid,
    requested_by uuid,
    supplier_id uuid,
    warehouse_id uuid,
    po_no varchar(20),
    status varchar(20) check ((status in ('PENDING','APPROVED','IN_TRANSIT','REJECTED','RECEIVED'))),
    decision_reason varchar(500),
    primary key (id)
);

create table purchase_order_item (
    qty integer,
    unit_price_krw integer,
    box_spec_id uuid not null,
    purchase_order_id uuid not null,
    box_snapshot jsonb,
    primary key (box_spec_id, purchase_order_id)
);

create table supplier (
    lead_time_days smallint,
    id uuid not null,
    warehouse_id uuid,
    name varchar(100),
    payment_terms varchar(100),
    primary key (id)
);

create table warehouse (
    id uuid not null,
    name varchar(100),
    receiving_contact varchar(100),
    receiving_hours varchar(100),
    address varchar(255),
    primary key (id)
);

-- 조회 인덱스 (PK/UNIQUE가 만드는 인덱스는 별도 선언하지 않는다)
create index ix_job_warehouse_month
   on analysis_job (warehouse_id, target_month, created_at);

create index ix_cost_warehouse_month
   on cost_forecast (warehouse_id, target_month, generated_at);

create index ix_event_warehouse_time
   on item_event (warehouse_id, occurred_at);

create index ix_event_warehouse_status
   on item_event (warehouse_id, status);

create index ix_notification_recipient_created
   on notification (recipient_id, created_at);

create index ix_proposal_warehouse_month
   on procurement_proposal (warehouse_id, target_month, generated_at);

create index ix_purchase_warehouse_created
   on purchase_order (warehouse_id, created_at);

-- 실제 JPA 연관관계의 외래키 (제약 이름은 Hibernate 생성 결과)
alter table if exists analysis_job
   add constraint FKbvl065kadcioey9krerwf8bdq
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists app_user
   add constraint FKiehf3uwvf3m2546l0o4xb7ug8
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists box_spec
   add constraint FK8xwdtwhjm6xli4yxxltapv82h
   foreign key (supplier_id)
   references supplier (id);

alter table if exists box_spec
   add constraint FKgfb966fbfa03u6cpst1wiswtq
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists category
   add constraint FK35ui0a62a3bducjxwfeb2rugw
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists cost_forecast
   add constraint FKsy52ehhchok7pmryosc6nhycf
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists item_event
   add constraint FK57lu7ihbhrme7ymnnijywp4fq
   foreign key (box_spec_id)
   references box_spec (id);

alter table if exists item_event
   add constraint FK9dm38jgmn9u5tuqgfq9q6sidj
   foreign key (product_id)
   references product (id);

alter table if exists item_event
   add constraint FKsag4kr54bejk16vbcg59uvtla
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists notification
   add constraint FKeg1j4hnp0y4lbm0y35hgr4e8r
   foreign key (recipient_id)
   references app_user (id);

alter table if exists procurement_proposal
   add constraint FKm7eor4kfcg7gw1ano2153wiyf
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists procurement_proposal_item
   add constraint FK22uebw35ki4gv3cyskfjqta4m
   foreign key (box_spec_id)
   references box_spec (id);

alter table if exists procurement_proposal_item
   add constraint FKo0okj6guaeripjmqbcy6jihev
   foreign key (proposal_id)
   references procurement_proposal (id);

alter table if exists product
   add constraint FK1mtsbur82frn64de7balymq9s
   foreign key (category_id)
   references category (id);

alter table if exists product
   add constraint FKswwxlnn9ts8qhd8jk1bpfsp5h
   foreign key (matched_box_spec_id)
   references box_spec (id);

alter table if exists product
   add constraint FKk6edvfdkq61mjhltx856ncs3x
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists purchase_order
   add constraint FK59628olaexkfhqcabu807718r
   foreign key (approver_id)
   references app_user (id);

alter table if exists purchase_order
   add constraint FK7w0j91e3s5nchu5p3nl6mmimo
   foreign key (proposal_id)
   references procurement_proposal (id);

alter table if exists purchase_order
   add constraint FK4nh2pwhkegcftgj7qq1cbkwvl
   foreign key (requested_by)
   references app_user (id);

alter table if exists purchase_order
   add constraint FK4traogu3jriq9u7e8rvm86k7i
   foreign key (supplier_id)
   references supplier (id);

alter table if exists purchase_order
   add constraint FKfn0kri8xb0mi8bkb2swlnv581
   foreign key (warehouse_id)
   references warehouse (id);

alter table if exists purchase_order_item
   add constraint FKj900002c4oar3u7rrfc3imucc
   foreign key (box_spec_id)
   references box_spec (id);

alter table if exists purchase_order_item
   add constraint FKmj122necubadvuquvjoq967y7
   foreign key (purchase_order_id)
   references purchase_order (id);

alter table if exists supplier
   add constraint FKpbqyoji0x1k60h5lmisr2ikn5
   foreign key (warehouse_id)
   references warehouse (id);
