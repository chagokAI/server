# API 구현 현황

[x] 구현, [ ] 미구현. 응답 필드와 집계 기준은 [조회 API 응답 계약](read-api-contracts.md)을 참고한다.

와이어프레임의 Spring 업무 흐름을 구현한다. 새로고침 정책 조회는 직접 구현용으로 남겨둔다.
세션 로그인·센터/역할 검증, 입고·실사 조정, 공급사별 발주 초안·수정·승인·부분 입고를 제공한다.
분석 작업 POST·재시도·정기 실행은 OBSERVATION_BASELINE 방식의 참고 계산이다.
로그인·CSRF 토큰 조회·health 외 API는 로그인 필요. 조회·로그아웃·본인 알림 읽음은 VIEWER 이상,
업무 변경은 OPERATOR/DIRECTOR이며 모든 센터 API는 로그인 사용자의 소속 센터로 제한한다.

- [x] **AUTH-API-01** — `POST /api/v1/auth/login` — LoginInput → 200 UserSession, 세션 쿠키 / AUTH-01~02
- [x] **AUTH-API-02** — `GET /api/v1/auth/me` — 없음 → 200 UserSession / AUTH-03
- [x] **AUTH-API-03** — `POST /api/v1/auth/logout` — 없음 → 204, 세션 종료 / AUTH-04

- [x] **COMMON-API-02** — `GET /api/v1/warehouses/{warehouseId}/suppliers` — keyword? → 200 Supplier[] / SPEC-03, PO-06 (이름 가나다순)
- [ ] **COMMON-API-03** — `GET /api/v1/warehouses/{warehouseId}/refresh-policy` — 없음 → 200 RefreshPolicy / COM-02 **직접 구현용으로 남김**

- [x] **DASH-API-01** — `GET /api/v1/warehouses/{warehouseId}/dashboard` — targetMonth? 기본 다음 달 → 200 Dashboard / DASH-01,04~10
- [x] **DASH-API-02** — `GET /api/v1/warehouses/{warehouseId}/simulation-state` — limit? 기본 100, 최대 500; 최근 관측 순, 기간 지정 시 시간·이벤트 ID 커서 페이지, DL 치수와 선택적 availableRange → 200 Scene / DASH-02~03 ([3D 연동](digital-twin.md))
- [x] **DASH-API-03** — `GET /api/v1/warehouses/{warehouseId}/metrics` — metric, from, to, bucket=day\|hour, boxSpecId? → 200 MetricSeries / 추이 확인 확장

- [x] **INV-API-03** — `POST /api/v1/warehouses/{warehouseId}/inventory-receipts` — ReceiptInput → 201 Receipt / INV-04~05
- [x] **INV-API-04** — `POST /api/v1/warehouses/{warehouseId}/inventory-adjustments` — AdjustmentInput → 201 Adjustment / INV-06

- [x] **PO-API-01** — `GET /api/v1/warehouses/{warehouseId}/procurement-proposals/latest` — targetMonth → 200 LatestProposal / PO-01~03
- [x] **PO-API-02** — `GET /api/v1/warehouses/{warehouseId}/procurement-proposals/{proposalId}` — 없음 → 200 Proposal / 저장 버전 조회
- [x] **PO-API-03** — `GET /api/v1/warehouses/{warehouseId}/cost-forecasts/latest` — targetMonth → 200 LatestCostForecast / DASH-07, PO-03
- [x] **PO-API-04** — `POST /api/v1/warehouses/{warehouseId}/purchase-orders/from-proposal` — DraftFromProposal → 201 DraftGroup(공급사별 PENDING 초안) 또는 기존 생성 건 200 / PO-04
- [x] **PO-API-05** — `PATCH /api/v1/warehouses/{warehouseId}/purchase-orders/{purchaseOrderId}` — PurchaseOrderUpdate → 200 PurchaseOrder / PO-05~06

- [x] **HIST-API-01** — `GET /api/v1/warehouses/{warehouseId}/purchase-orders` — from, to, keyword?, status?, 페이지; sort 기본 createdAt,desc / deliveryDate → 200 PurchaseOrderPage(목록+summary) / HIST-01~03
- [x] **HIST-API-02** — `GET /api/v1/warehouses/{warehouseId}/purchase-orders/{purchaseOrderId}` — 없음 → 200 PurchaseOrder / HIST-04~06
- [x] **HIST-API-03** — `GET /api/v1/warehouses/{warehouseId}/purchase-orders/{purchaseOrderId}/pdf` — 없음 → 200 application/pdf(첨부 파일) / HIST-04 상세의 PDF 다운로드

- [x] **NOTI-API-01** — `GET /api/v1/warehouses/{warehouseId}/notifications` — category?, unreadOnly?, 페이지; sort createdAt,desc → 200 NotificationPage / NOTI-01~02
- [x] **NOTI-API-02** — `PATCH /api/v1/warehouses/{warehouseId}/notifications/{notificationId}/read` — 없음 → 200 Notification / NOTI-03~04
- [x] **NOTI-API-03** — `POST /api/v1/warehouses/{warehouseId}/notifications/read-all` — {through?: datetime} → 200 {updatedCount,unreadCount} / NOTI-03
- [x] **JOB-API-01** — `GET /api/v1/warehouses/{warehouseId}/jobs/{jobId}` — 없음 → 200 Job / 자동 생성 상태 조회
- [x] **JOB-API-02** — `POST /api/v1/warehouses/{warehouseId}/ai-jobs` — {targetMonth, type} → 202 Job + Location, type=PROCUREMENT_AND_COST
- [x] **JOB-API-03** — `POST /api/v1/warehouses/{warehouseId}/jobs/{jobId}/retry` — {expectedVersion} → 202 Job + Location, 새 실행 시도 생성

## 기존 구현 및 이번 응답 보완

- [x] COMMON-API-01 — `GET /api/v1/warehouses/{warehouseId}/categories` — 카테고리 목록
- [x] MATCH-API-01 — `GET /api/v1/warehouses/{warehouseId}/product-matches` — 상품·매칭·상태별 개수·페이지
- [x] MATCH-API-02 — `GET /api/v1/warehouses/{warehouseId}/products/{productId}` — 상품 상세·수동 지정 사유·version
- [x] MATCH-API-03~05 — 상품 수정(치수 변경 시 1순위 자동 적용)·수동 박스 지정·비활성화
- [x] (추가) `POST /api/v1/warehouses/{warehouseId}/product-matches/recalculate` — 미매칭 상품에 치수 기준 1순위 일괄 적용
- [x] BOXSTOCK-API-01 — `GET /api/v1/warehouses/{warehouseId}/box-stocks` — 박스 재고/규격 관리 목록·연결 상품 수·규격별 재고(stock)·사용 여부 개수·요약
- [x] SPEC-API-02 — `GET /api/v1/warehouses/{warehouseId}/box-specs/{boxSpecId}` — 상세·연결 상품 수
- [x] SPEC-API-03~05 — 규격 생성·수정·미사용이고 참조/재고 없는 규격 삭제(막히면 409 details)
- [x] (추가) `GET /api/v1/box-materials` — 재질 드롭다운 선택지 `[{code, label}]`
- [x] INV-API-02 — 안전 재고 정책 수정
- [x] 기존 발주 생성 — `POST /api/v1/warehouses/{warehouseId}/purchase-orders` — PENDING 발주서 생성·품목 규격 snapshot 저장

기존 발주 생성 POST는 단일 공급사 직접 생성용 호환 경로다. 생성만 하고 승인은 PATCH로 한다. 신규 화면은 from-proposal → PATCH 흐름을 사용한다.
JOB-API-01~03과 DB 대기열 실행기·5분 정기 스케줄러를 구현했다.
성공 관측 이력 기반 참고 계산을 사용하며 실제 소모량·학습 모델·Kafka 연동 변경은 이번 범위에 포함하지 않는다.
인증은 기본 활성화된다. 세션·CSRF·초기 로컬 계정 생성 방법은 [로컬 개발](local-development.md)을 참고한다.

## 화면 흐름을 위한 추가 API

- [x] AUTH-CSRF — `GET /api/v1/auth/csrf` — 세션 CSRF 토큰·헤더명
- [x] MATCH-CANDIDATES — `GET /api/v1/warehouses/{warehouseId}/products/{productId}/box-candidates` — 실제 치수로 계산한 적합 후보
- [x] INV-HISTORY — `GET /api/v1/warehouses/{warehouseId}/box-inventories/{boxSpecId}/movements` — 입고·실사 이력, 페이지
- [x] PO-OPEN — `GET /api/v1/warehouses/{warehouseId}/purchase-orders/open?boxSpecId=` — 입고 가능한 발주서·미입고 수량

바코드·무게, 안전 재고 기준 일수·알림 설정, 치수 변경 후 매칭 재검증,
재고 미확인 상태, 진행 중 발주 집계·금액 정렬을 보완했다.
업무 변경에 따른 알림은 DB에 저장하며 공급사 전송·외부 메시지 발송은 수행하지 않는다.
프론트 적용 시 필요한 필드 변환은 [연동 계약](frontend-integration.md)을 참고한다.
