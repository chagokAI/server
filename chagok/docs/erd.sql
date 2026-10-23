CREATE TABLE `warehouse` (
                             `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                             `name` VARCHAR(100),
                             `receiving_contact` VARCHAR(100),
                             `receiving_hours` VARCHAR(100),
                             `address` VARCHAR(255),
                             `latitude` DOUBLE COMMENT '위도 (WGS84)',
                             `longitude` DOUBLE COMMENT '경도 (WGS84)',
                             PRIMARY KEY (`id`)
) COMMENT = '물류센터';

CREATE TABLE `app_user` (
                            `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                            `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                            `role` VARCHAR(20),
                            `employee_no` VARCHAR(30),
                            `department` VARCHAR(50),
                            `job_title` VARCHAR(50),
                            `name` VARCHAR(50),
                            `password_hash` VARCHAR(255),
                            PRIMARY KEY (`id`)
) COMMENT = '사용자';

CREATE TABLE `supplier` (
                            `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                            `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                            `lead_time_days` SMALLINT,
                            `name` VARCHAR(100),
                            `payment_terms` VARCHAR(100),
                            PRIMARY KEY (`id`)
) COMMENT = '공급업체';

CREATE TABLE `category` (
                            `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                            `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                            `name` VARCHAR(50),
                            PRIMARY KEY (`id`)
) COMMENT = '상품 카테고리';

CREATE TABLE `box_spec` (
                            `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                            `supplier_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                            `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                            `alert_enabled` BOOLEAN,
                            `days_to_stockout` INTEGER,
                            `inner_depth_mm` INTEGER,
                            `inner_height_mm` INTEGER,
                            `inner_width_mm` INTEGER,
                            `is_active` BOOLEAN,
                            `is_custom` BOOLEAN,
                            `on_hand_qty` INTEGER,
                            `safety_days` INTEGER,
                            `safety_qty` INTEGER,
                            `size_class` CHAR(1),
                            `target_qty` INTEGER,
                            `unit_price_krw` INTEGER,
                            `version` INTEGER NOT NULL,
                            `forecast_updated_at` DATETIME(6) COMMENT 'PostgreSQL: timestamptz',
                            `stock_updated_at` DATETIME(6) COMMENT 'PostgreSQL: timestamptz',
                            `code` VARCHAR(20),
                            `material` VARCHAR(50),
                            `name` VARCHAR(50),
                            `description` VARCHAR(200),
                            PRIMARY KEY (`id`)
) COMMENT = '박스 규격 및 재고';

CREATE TABLE `product` (
                           `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                           `category_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                           `matched_box_spec_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                           `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                           `depth_mm` INTEGER,
                           `fill_rate_pct` NUMERIC(5,2),
                           `height_mm` INTEGER,
                           `is_active` BOOLEAN,
                           `version` INTEGER NOT NULL,
                           `weight_g` INTEGER,
                           `width_mm` INTEGER,
                           `match_source` VARCHAR(10),
                           `match_status` VARCHAR(20) COMMENT '허용값: MATCHED, REVIEW, UNMATCHED',
                           `barcode` VARCHAR(30),
                           `code` VARCHAR(30),
                           `name` VARCHAR(100),
                           `match_override_reason` VARCHAR(200),
                           `image_url` VARCHAR(500),
                           `embedding` TEXT COMMENT 'PostgreSQL: vector(384), 384차원 임베딩',
                           PRIMARY KEY (`id`),
                           UNIQUE KEY `uq_product_barcode` (`warehouse_id`, `barcode`)
) COMMENT = '상품';

CREATE TABLE `analysis_job` (
                                `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                `warehouse_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                `version` INTEGER NOT NULL,
                                `target_month` VARCHAR(7) NOT NULL,
                                `completed_at` DATETIME(6) COMMENT 'PostgreSQL: timestamp(6) with time zone',
                                `created_at` DATETIME(6) NOT NULL COMMENT 'PostgreSQL: timestamp(6) with time zone',
                                `data_as_of` DATETIME(6) COMMENT 'PostgreSQL: timestamp(6) with time zone',
                                `started_at` DATETIME(6) COMMENT 'PostgreSQL: timestamp(6) with time zone',
                                `cost_forecast_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                `proposal_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                `retry_job_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                `retry_of_job_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                `status` VARCHAR(20) NOT NULL COMMENT '허용값: PENDING, RUNNING, SUCCEEDED, FAILED, PUBLISH_FAILED, TIMED_OUT',
                                `trigger_source` VARCHAR(20) COMMENT '허용값: MANUAL, SCHEDULED, RETRY',
                                `job_type` VARCHAR(40) NOT NULL COMMENT '허용값: PROCUREMENT_AND_COST',
                                `failure_code` VARCHAR(80),
                                `active_key` VARCHAR(100),
                                `failure_message` VARCHAR(500),
                                PRIMARY KEY (`id`),
                                UNIQUE KEY `uq_analysis_job_active_key` (`active_key`),
                                KEY `ix_job_warehouse_month` (`warehouse_id`, `target_month`, `created_at`)
) COMMENT = '분석 작업';

CREATE TABLE `procurement_proposal` (
                                        `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                        `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                        `target_month` CHAR(7),
                                        `data_as_of` DATETIME(6) COMMENT 'PostgreSQL: timestamptz',
                                        `generated_at` DATETIME(6) NOT NULL COMMENT 'PostgreSQL: timestamptz',
                                        `job_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                        `summary` JSON COMMENT 'PostgreSQL: jsonb',
                                        PRIMARY KEY (`id`),
                                        KEY `ix_proposal_warehouse_month` (`warehouse_id`, `target_month`, `generated_at`)
) COMMENT = '조달 제안';

CREATE TABLE `procurement_proposal_item` (
                                             `box_spec_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                             `proposal_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                             `forecast_usage_qty` INTEGER,
                                             `on_hand_qty` INTEGER,
                                             `proposed_qty` INTEGER,
                                             `safety_qty` INTEGER,
                                             `unit_price_krw` INTEGER,
                                             `box_snapshot` JSON COMMENT 'PostgreSQL: jsonb',
                                             PRIMARY KEY (`box_spec_id`, `proposal_id`)
) COMMENT = '조달 제안 항목';

CREATE TABLE `cost_forecast` (
                                 `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                 `warehouse_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                 `target_month` VARCHAR(7) NOT NULL,
                                 `data_as_of` DATETIME(6) COMMENT 'PostgreSQL: timestamp(6) with time zone',
                                 `generated_at` DATETIME(6) NOT NULL COMMENT 'PostgreSQL: timestamp(6) with time zone',
                                 `job_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                 `result` JSON NOT NULL COMMENT 'PostgreSQL: jsonb',
                                 PRIMARY KEY (`id`),
                                 KEY `ix_cost_warehouse_month` (`warehouse_id`, `target_month`, `generated_at`)
) COMMENT = '비용 예측 결과';

CREATE TABLE `purchase_order` (
                                  `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                  `approver_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                  `proposal_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                  `requested_by` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                  `supplier_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                  `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                  `requested_delivery_date` DATE,
                                  `version` INTEGER NOT NULL,
                                  `approved_at` DATETIME(6) COMMENT 'PostgreSQL: timestamptz',
                                  `created_at` DATETIME(6) NOT NULL COMMENT 'PostgreSQL: timestamptz',
                                  `received_at` DATETIME(6) COMMENT 'PostgreSQL: timestamptz',
                                  `updated_at` DATETIME(6) COMMENT 'PostgreSQL: timestamptz',
                                  `po_no` VARCHAR(20),
                                  `status` VARCHAR(20) COMMENT '허용값: PENDING, APPROVED, IN_TRANSIT, REJECTED, RECEIVED',
                                  `decision_reason` VARCHAR(500),
                                  PRIMARY KEY (`id`),
                                  KEY `ix_purchase_warehouse_created` (`warehouse_id`, `created_at`)
) COMMENT = '발주서';

CREATE TABLE `purchase_order_item` (
                                       `box_spec_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                       `purchase_order_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                       `qty` INTEGER,
                                       `unit_price_krw` INTEGER,
                                       `box_snapshot` JSON COMMENT 'PostgreSQL: jsonb',
                                       PRIMARY KEY (`box_spec_id`, `purchase_order_id`)
) COMMENT = '발주 항목';

CREATE TABLE `item_event` (
                              `occurred_at` DATETIME(6) NOT NULL COMMENT 'PostgreSQL: timestamptz',
                              `event_id` VARCHAR(64) NOT NULL,
                              `box_spec_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                              `product_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                              `warehouse_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                              `fill_rate_pct` NUMERIC(5,2),
                              `status` VARCHAR(10),
                              `placement` JSON COMMENT 'PostgreSQL: jsonb',
                              PRIMARY KEY (`occurred_at`, `event_id`),
                              KEY `ix_event_warehouse_time` (`warehouse_id`, `occurred_at`),
                              KEY `ix_event_warehouse_status` (`warehouse_id`, `status`)
) COMMENT = '상품 관측 이벤트';

CREATE TABLE `inventory_movement` (
                                      `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                      `after_qty` INTEGER NOT NULL,
                                      `before_qty` INTEGER,
                                      `occurred_on` DATE NOT NULL,
                                      `created_at` DATETIME(6) NOT NULL COMMENT 'PostgreSQL: timestamp(6) with time zone',
                                      `delta_qty` BIGINT,
                                      `box_spec_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                      `purchase_order_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                      `request_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                      `warehouse_id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                      `type` VARCHAR(20) NOT NULL,
                                      `memo` VARCHAR(500),
                                      PRIMARY KEY (`id`),
                                      UNIQUE KEY `uq_inventory_request` (`warehouse_id`, `request_id`)
) COMMENT = '재고 변동 이력';

CREATE TABLE `notification` (
                                `id` CHAR(36) NOT NULL COMMENT 'PostgreSQL: uuid',
                                `recipient_id` CHAR(36) COMMENT 'PostgreSQL: uuid',
                                `created_at` DATETIME(6) NOT NULL COMMENT 'PostgreSQL: timestamptz',
                                `read_at` DATETIME(6) COMMENT 'PostgreSQL: timestamptz',
                                `category` VARCHAR(20),
                                `link` VARCHAR(200),
                                `title` VARCHAR(200),
                                `message` VARCHAR(500),
                                PRIMARY KEY (`id`),
                                KEY `ix_notification_recipient_created` (`recipient_id`, `created_at`)
) COMMENT = '알림';

ALTER TABLE `analysis_job`
    ADD CONSTRAINT `fk_analysis_job_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `app_user`
    ADD CONSTRAINT `fk_app_user_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `box_spec`
    ADD CONSTRAINT `fk_box_spec_supplier_id`
        FOREIGN KEY (`supplier_id`)
            REFERENCES `supplier` (`id`);

ALTER TABLE `box_spec`
    ADD CONSTRAINT `fk_box_spec_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `category`
    ADD CONSTRAINT `fk_category_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `cost_forecast`
    ADD CONSTRAINT `fk_cost_forecast_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `item_event`
    ADD CONSTRAINT `fk_item_event_box_spec_id`
        FOREIGN KEY (`box_spec_id`)
            REFERENCES `box_spec` (`id`);

ALTER TABLE `item_event`
    ADD CONSTRAINT `fk_item_event_product_id`
        FOREIGN KEY (`product_id`)
            REFERENCES `product` (`id`);

ALTER TABLE `item_event`
    ADD CONSTRAINT `fk_item_event_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `notification`
    ADD CONSTRAINT `fk_notification_recipient_id`
        FOREIGN KEY (`recipient_id`)
            REFERENCES `app_user` (`id`);

ALTER TABLE `procurement_proposal`
    ADD CONSTRAINT `fk_procurement_proposal_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `procurement_proposal_item`
    ADD CONSTRAINT `fk_procurement_proposal_item_box_spec_id`
        FOREIGN KEY (`box_spec_id`)
            REFERENCES `box_spec` (`id`);

ALTER TABLE `procurement_proposal_item`
    ADD CONSTRAINT `fk_procurement_proposal_item_proposal_id`
        FOREIGN KEY (`proposal_id`)
            REFERENCES `procurement_proposal` (`id`);

ALTER TABLE `product`
    ADD CONSTRAINT `fk_product_category_id`
        FOREIGN KEY (`category_id`)
            REFERENCES `category` (`id`);

ALTER TABLE `product`
    ADD CONSTRAINT `fk_product_matched_box_spec_id`
        FOREIGN KEY (`matched_box_spec_id`)
            REFERENCES `box_spec` (`id`);

ALTER TABLE `product`
    ADD CONSTRAINT `fk_product_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `purchase_order`
    ADD CONSTRAINT `fk_purchase_order_approver_id`
        FOREIGN KEY (`approver_id`)
            REFERENCES `app_user` (`id`);

ALTER TABLE `purchase_order`
    ADD CONSTRAINT `fk_purchase_order_proposal_id`
        FOREIGN KEY (`proposal_id`)
            REFERENCES `procurement_proposal` (`id`);

ALTER TABLE `purchase_order`
    ADD CONSTRAINT `fk_purchase_order_requested_by`
        FOREIGN KEY (`requested_by`)
            REFERENCES `app_user` (`id`);

ALTER TABLE `purchase_order`
    ADD CONSTRAINT `fk_purchase_order_supplier_id`
        FOREIGN KEY (`supplier_id`)
            REFERENCES `supplier` (`id`);

ALTER TABLE `purchase_order`
    ADD CONSTRAINT `fk_purchase_order_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);

ALTER TABLE `purchase_order_item`
    ADD CONSTRAINT `fk_purchase_order_item_box_spec_id`
        FOREIGN KEY (`box_spec_id`)
            REFERENCES `box_spec` (`id`);

ALTER TABLE `purchase_order_item`
    ADD CONSTRAINT `fk_purchase_order_item_purchase_order_id`
        FOREIGN KEY (`purchase_order_id`)
            REFERENCES `purchase_order` (`id`);

ALTER TABLE `supplier`
    ADD CONSTRAINT `fk_supplier_warehouse_id`
        FOREIGN KEY (`warehouse_id`)
            REFERENCES `warehouse` (`id`);
