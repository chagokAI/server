# API 구현 현황

[x] 구현, [ ] 미구현. 응답 필드와 집계 기준은 [조회 API 응답 계약](read-api-contracts.md)을 참고한다.

이번 범위는 조회 API와 기존 조회 응답 보완이다. 공급사 조회·새로고침 정책 조회·알림 읽음 처리 두 개는 직접 구현할 수 있도록 남겨둔다.
인증, 입고·재고 조정, 발주 초안 생성·변경, 분석 작업 POST·정기 배치 실행기는 후속 범위다.
명세상 권한은 조회 API·본인 알림 읽음·로그아웃은 조회자 이상, 그 외 변경 API는 운영자 이상, 로그인은 공개다.
권한 적용은 AUTH 구현과 함께 연결해야 한다.

- [ ] **AUTH-API-01** — `POST /api/v1/auth/login` — LoginInput → 200 UserSession, 세션 쿠키 / AUTH-01~02
- [ ] **AUTH-API-02** — `GET /api/v1/auth/me` — 없음 → 200 UserSession / AUTH-03
- [ ] **AUTH-API-03** — `POST /api/v1/auth/logout` — 없음 → 204, 세션 종료 / AUTH-04

- [ ] **COMMON-API-02** — `GET /api/v1/warehouses/{warehouseId}/suppliers` — keyword? → 200 Supplier[] / SPEC-03, PO-06 **직접 구현용으로 남김**
- [ ] **COMMON-API-03** — `GET /api/v1/warehouses/{warehouseId}/refresh-policy` — 없음 → 200 RefreshPolicy / COM-02 **직접 구현용으로 남김**

- [x] **DASH-API-01** — `GET /api/v1/warehouses/{warehouseId}/dashboard` — targetMonth? 기본 다음 달 → 200 Dashboard / DASH-01,04~10
- [x] **DASH-API-02** — `GET /api/v1/warehouses/{warehouseId}/simulation-state` — limit? 기본 100, 최대 500; 최근 관측 순, boxId로 동률 정렬 → 200 Scene / DASH-02~03
- [x] **DASH-API-03** — `GET /api/v1/warehouses/{warehouseId}/metrics` — metric, from, to, bucket=day\|hour, boxSpecId? → 200 MetricSeries / 추이 확인 확장

- [ ] **INV-API-03** — `POST /api/v1/warehouses/{warehouseId}/inventory-receipts` — ReceiptInput → 201 Receipt / INV-04~05
- [ ] **INV-API-04** — `POST /api/v1/warehouses/{warehouseId}/inventory-adjustments` — AdjustmentInput → 201 Adjustment / INV-06

- [x] **PO-API-01** — `GET /api/v1/warehouses/{warehouseId}/procurement-proposals/latest` — targetMonth → 200 LatestProposal / PO-01~03
- [x] **PO-API-02** — `GET /api/v1/warehouses/{warehouseId}/procurement-proposals/{proposalId}` — 없음 → 200 Proposal / 저장 버전 조회
- [x] **PO-API-03** — `GET /api/v1/warehouses/{warehouseId}/cost-forecasts/latest` — targetMonth → 200 LatestCostForecast / DASH-07, PO-03
- [ ] **PO-API-04** — `POST /api/v1/warehouses/{warehouseId}/purchase-orders/from-proposal` — DraftFromProposal → 201 DraftGroup 또는 기존 생성 건 200 / PO-04
- [ ] **PO-API-05** — `PATCH /api/v1/warehouses/{warehouseId}/purchase-orders/{purchaseOrderId}` — PurchaseOrderUpdate → 200 PurchaseOrder / PO-05~06

- [x] **HIST-API-01** — `GET /api/v1/warehouses/{warehouseId}/purchase-orders` — from, to, keyword?, status?, 페이지; sort 기본 createdAt,desc / deliveryDate → 200 PurchaseOrderPage(목록+summary) / HIST-01~03
- [x] **HIST-API-02** — `GET /api/v1/warehouses/{warehouseId}/purchase-orders/{purchaseOrderId}` — 없음 → 200 PurchaseOrder / HIST-04~06

- [x] **NOTI-API-01** — `GET /api/v1/warehouses/{warehouseId}/notifications` — category?, unreadOnly?, 페이지; sort createdAt,desc → 200 NotificationPage / NOTI-01~02
- [ ] **NOTI-API-02** — `PATCH /api/v1/warehouses/{warehouseId}/notifications/{notificationId}/read` — 없음 → 200 Notification / NOTI-03~04 **직접 구현용으로 남김**
- [ ] **NOTI-API-03** — `POST /api/v1/warehouses/{warehouseId}/notifications/read-all` — {through: datetime} → 200 {updatedCount,unreadCount} / NOTI-03 **직접 구현용으로 남김**
- [x] **JOB-API-01** — `GET /api/v1/warehouses/{warehouseId}/jobs/{jobId}` — 없음 → 200 Job / 자동 생성 상태 조회
- [ ] **JOB-API-02** — `POST /api/v1/warehouses/{warehouseId}/ai-jobs` — {targetMonth, type} → 202 Job / PO-10 확장, type=PROCUREMENT_AND_COST
- [ ] **JOB-API-03** — `POST /api/v1/warehouses/{warehouseId}/jobs/{jobId}/retry` — VersionInput → 202 Job / 실패·전송 실패·기한 초과 재실행용 확장 UI

## 기존 구현 및 이번 응답 보완

- [x] COMMON-API-01 — `GET /api/v1/warehouses/{warehouseId}/categories` — 카테고리 목록
- [x] MATCH-API-01 — `GET /api/v1/warehouses/{warehouseId}/product-matches` — 상품·매칭·상태별 개수·페이지
- [x] MATCH-API-02 — `GET /api/v1/warehouses/{warehouseId}/products/{productId}` — 상품 상세·수동 지정 사유·version
- [x] MATCH-API-03~05 — 상품 수정·수동 박스 지정·비활성화
- [x] SPEC-API-01 — `GET /api/v1/warehouses/{warehouseId}/box-specs` — 목록·연결 상품 수·상태별 개수·요약
- [x] SPEC-API-02 — `GET /api/v1/warehouses/{warehouseId}/box-specs/{boxSpecId}` — 상세·연결 상품 수
- [x] SPEC-API-03~05 — 규격 생성·수정·참조/재고 없는 규격 삭제
- [x] INV-API-01 — `GET /api/v1/warehouses/{warehouseId}/box-inventories` — 목록·요약·예측 출처·재고/예측 갱신 시각
- [x] INV-API-02 — 안전 재고 정책 수정
- [x] 기존 발주 승인 — `POST /api/v1/warehouses/{warehouseId}/purchase-orders` — APPROVED 발주서 생성·품목 규격 snapshot 저장

기존 발주 승인 POST는 PO-API-04의 공급사별 초안 생성과 다른 기능이다.
JOB-API-01은 저장 상태 조회만 구현했다. 모델 호출, 결과 생성·저장 실행기, POST 트리거, 재시도, 정기 실행은 아직 없다.
로그인·권한 처리는 기존 임시 SecurityConfig/CurrentUserProvider를 사용하므로 AUTH 구현과 함께 연결해야 한다.
