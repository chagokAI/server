# 조회 API 응답 계약

공통 경로 W는 `/api/v1/warehouses/{warehouseId}`다. 치수는 mm, 수량은 개, 금액은 VAT 별도 KRW다.
이 문서는 화면 시안과 저장 가능한 데이터에 맞춘 계약이다. 모든 GET은 조회만 하며 배치를 실행하지 않는다.

## 공통 형식

성공 응답은 `{data, meta}`다. 목록 data에는 `items`, `page`(0부터), `size`(기본 20, 최대 100),
`totalElements`, `totalPages`가 있다. 상태 탭은 `counts`, 요약 카드는 `summary`를 추가한다.
없는 센터·다른 센터의 상세 리소스는 404, 잘못된 입력은 공통 오류 형식의 400이다.

| meta 필드 | 의미 |
| --- | --- |
| servedAt | 응답 생성 시각. 센터 시간대(기본 Asia/Seoul) |
| dataAsOf | 저장 결과의 입력 데이터 기준 시각. 모르는 경우·여러 시점을 섞는 응답은 null |
| refreshAfterSeconds | 화면 권장 재조회 간격(기본 300초). 배치 실행 주기와 별개 |
| pendingEventCount | 시뮬레이션에서 해당 센터의 PENDING 관측 수. 다른 응답은 null |

`generatedAt`은 저장 결과의 생성 시각이다. `version`은 낙관적 잠금용이며 작업·배치 결과 ID와 구분한다.

## 최신 배치 결과

제안·비용 최신 조회 data는 `{targetMonth, dataStatus, result, latestJob}`다.

- `dataStatus`: NOT_GENERATED 또는 AVAILABLE
- 미생성은 result=null. 실제 수량·금액이 0인 결과는 AVAILABLE
- 새 작업이 RUNNING/FAILED여도 마지막 성공 결과를 result에 유지
- latestJob은 같은 센터·대상 월·종류의 최근 작업. 작업이 없으면 null
- targetMonth는 두 latest API에서 필수이며 정확한 YYYY-MM 형식
- 성공 결과 테이블에는 완료된 결과만 기록하고 중간 결과는 기록하지 않아야 함

## DASH-API-01: 대시보드

`GET W/dashboard?targetMonth=2026-11` — targetMonth 기본 다음 달.

- currentMonth, targetMonth
- inventory: 재고 화면과 같은 전체 요약
- stockBySize[]: sizeClass, onHandQty, safetyQty (S/M/L/OTHER)
- shortages[]: boxSpecId, code, name, onHandQty, safetyQty, shortageQty, daysToStockout
- projectedStockQty: 현재 재고 + 제안 수량 - 예상 소모량. 다음 달이고 입력이 완전할 때만 계산
- demandForecast: proposal(최신 배치 형식), totalForecastUsageQty, bySize[{sizeClass,forecastUsageQty,sharePct}]
- currentMonthCost, targetMonthCost: 이번 달·대상 월의 비용 예측(최신 배치 형식)

인사이트·출고 예측·정확도는 저장된 proposal.result.analysis와 비용 결과 forecast.insights에 담는다.
실제 출고·절감액의 근거가 없으면 관측 건수나 임의 수치로 대체하지 않는다.
재고와 예측의 기준 시각이 다를 수 있어 각 구성 요소의 시각을 제공한다.

## DASH-API-02: 시뮬레이션

`GET W/simulation-state?limit=100` — limit 1~500.

- items[]: boxId, eventId, observedAt, status, product, boxSpec, fillRatePct, placement, placementAvailable, dimensionsSource
- product: id, code, name, imageUrl, dimensions{widthMm,depthMm,heightMm}
- latestObservedAt, pendingEventCount, limit

이벤트 한 건이 시각화 인스턴스 한 개이므로 boxId=eventId다.
관측 시각 내림차순, 동률이면 eventId 오름차순이다.
치수는 현재 카탈로그이므로 dimensionsSource=CATALOG다. 관측 시각은 분석 완료 시각과 다르다.
배치 좌표가 없으면 placement=null, placementAvailable=false다.

## DASH-API-03: 지표 추이

`GET W/metrics?metric=OBSERVATION_COUNT&from=2026-10-01&to=2026-10-19&bucket=day`

- metric: OBSERVATION_COUNT / SUCCEEDED_COUNT / FAILED_COUNT / PENDING_COUNT / MATCHED_BOX_COUNT / AVG_FILL_RATE_PCT
- from/to: 센터 시간대 기준 ISO 날짜, 양 끝 날짜 포함, from <= to
- bucket: day(기본, 최대 366일) / hour(최대 31일)
- boxSpecId: 선택, 해당 센터의 규격이어야 함
- 응답: metric, unit(COUNT/PERCENT), from, to, bucket, timezone, boxSpecId, points[{at,value,sampleCount}]

빈 구간의 건수는 0, 평균은 null이다. 평균은 성공 관측의 null 아닌 포장공간비율 기준이다.
MATCHED_BOX_COUNT는 매칭 박스가 있는 성공 관측 수이며 실제 출고·재고 차감량과 다르다.

## 상품·박스·재고 조회

상품 매칭 목록·상세는 기존 DTO를 사용한다. 상품 정보·치수·카테고리·이미지, 매칭 박스·비율·상태·출처,
version과 상세의 수동 변경 사유를 반환한다. 미매칭은 박스·비율=null이다.
상품 목록 counts는 검색·카테고리 적용 후 상태 필터를 제외한 개수다.

박스 규격 목록·상세에 linkedProductCount(같은 센터의 활성 연결 상품 수)를 추가했다.
목록 counts는 ALL/ACTIVE/INACTIVE/CUSTOM, summary는 totalSpecCount/standardSpecCount/customSpecCount다.
counts·summary에는 검색어만 적용하고 활성·커스텀 필터는 제외한다.
연결 상품 수가 0이어도 비활성 상품·발주·제안·이벤트 참조나 재고 때문에 삭제가 제한될 수 있다.

재고 항목은 기존 재고·안전/목표 수량·재고 수준·소진일·상태·공급사/납기에 stockUpdatedAt와
forecastUpdatedAt를 구분해 제공한다. 기록되지 않은 시각은 null이다.
summary는 검색·상태와 무관한 센터 전체, counts는 검색만 적용한다.
summary.forecastSource는 proposalId/targetMonth/dataAsOf/generatedAt다.
주간 예상 소모량은 이번 달 제안의 월 소모량 × 7 ÷ 이번 달 일수(반올림)인 근사치다.
전주 비교 근거가 없어 weeklyForecastChangePct는 null이다.

## PO-API-01~02: 발주 제안

`GET W/procurement-proposals/latest?targetMonth=2026-11`

`GET W/procurement-proposals/{proposalId}` — 해당 저장 버전 상세.

ProposalResponse:

- id, jobId, targetMonth, dataAsOf, generatedAt
- totalQty, totalAmountKrw, forecastUsageQty, currency, vatIncluded, snapshotComplete
- analysis: 생성기가 기록한 제안 근거·정확도·출고 예측·인사이트 JSON
- items[]: boxSpec, onHandQty, safetyQty, forecastUsageQty, proposedQty, unitPriceKrw, amountKrw, snapshotAvailable

생성기는 품목의 box_snapshot·unit_price_krw에 당시 규격과 적용 단가를 저장해야 한다.
과거 품목에 snapshot이 없으면 표시 정보만 현재 카탈로그로 보완하고 snapshotAvailable=false를 반환한다.
저장 단가가 없으면 금액은 null이며 현재 단가로 과거 금액을 만들지 않는다.

## PO-API-03: 비용 예측

`GET W/cost-forecasts/latest?targetMonth=2026-11`

result: id, jobId, targetMonth, dataAsOf, generatedAt, currency, vatIncluded, forecast.

forecast:

- packagingCostKrw, packagingSavingsKrw, purchaseCostKrw
- previousMonth/previousYear: baseMonth, baseAmountKrw, changeAmountKrw, changePct
- items[]: 당시 boxSpec, forecastUsageQty, proposedQty, unitPriceKrw, packagingCostKrw, purchaseCostKrw
- insights[]

cost_forecast 저장 모델을 추가했다. 모델 호출·결과 생성기는 이번 조회 구현에 포함하지 않는다.
제안·비용을 함께 생성하면 같은 jobId로 성공 결과를 한 트랜잭션에서 저장해야 한다.

## HIST-API-01~02: 발주 이력

`GET W/purchase-orders?from=2026-08-01&to=2026-10-19`

- from/to 필수, 센터 시간대 양 끝 날짜 포함, 최대 5년
- keyword: 발주번호·공급사명 부분 검색. %/_는 문자로 검색
- status: PENDING / APPROVED / IN_TRANSIT / REJECTED / RECEIVED
- sort: createdAt,desc(기본) 또는 deliveryDate,asc|desc. 동률은 ID 오름차순
- 항목: id, poNo, createdAt, supplierId, supplierName, boxCodes, specCount, totalQty, totalAmountKrw, deliveryDate, status, version
- counts: ALL·상태별 개수. 기간·검색 적용, 상태 필터 제외
- summary: 동일한 전체 검색 범위의 totalOrderCount, totalAmountKrw, pendingCount, averageDeliveryLeadTimeDays, receivedSampleCount

요약 금액은 반려 제외·승인 대기 포함. 평균 납품 리드타임은 승인부터 실제 입고까지의 경과 일수이며 유효한 입고 표본만 사용한다.

`GET W/purchase-orders/{purchaseOrderId}`:

발주·제안 ID/상태/각 시각, 납품 희망일, 공급사·결제 조건·납기, 요청자·승인자,
수령 센터·주소·시간, 결정 사유, 품목·수량·저장 단가·금액·총계·version을 반환한다.
새 발주 품목은 당시 규격 snapshot을 저장한다. 과거 품목의 snapshot이 없으면 현재 표시 정보를 사용하고 표시한다.
공급사·담당자·수령지 정보는 현재 마스터 기준이다.

## NOTI-API-01: 알림

`GET W/notifications?category=INVENTORY&unreadOnly=true`

- category: INVENTORY / PURCHASE / AI / MATCHING, 생략 시 전체
- sort: createdAt,desc(기본) / createdAt,asc
- 항목: id, category, title, message, link, createdAt, readAt, unread
- unreadCount: 본인의 해당 센터 전체 미읽음 수. 카테고리·페이지·unreadOnly와 무관

본인에게 온 알림만 조회하며 다른 센터 소속이면 403이다. 기존 제목 없는 알림은 message를 title에도 제공한다.
현재 CurrentUserProvider는 인증 전 임시 사용자 선택 방식이다. 실제 세션 연결은 AUTH 작업에서 진행해야 한다.

## JOB-API-01: 작업 상태

`GET W/jobs/{jobId}`:

- id, targetMonth, type, status, createdAt, startedAt, completedAt, dataAsOf
- proposalId, costForecastId, failure{code,message}, retryable, version
- type: PROCUREMENT_AND_COST
- status: PENDING / RUNNING / SUCCEEDED / FAILED / PUBLISH_FAILED / TIMED_OUT
- retryable: FAILED/PUBLISH_FAILED/TIMED_OUT일 때 true(상태상 가능 여부)

작업 상태 모델과 조회만 구현했다. 접수·실행·재시도 POST·스케줄러는 후속이다.

## 검증과 스키마

`./gradlew offlineTest`: DTO·서비스·MockMvc 계약·JPA 쿼리 생성·H2 호환 모드 집계·내장 Kafka 검증.
실제 PostgreSQL·pgvector와 DL 전체 흐름 검증은 PostgreSQL 환경에서 `./gradlew test`로 확인한다.
개발 설정 ddl-auto=update는 새 테이블과 nullable snapshot 컬럼을 추가한다. 운영에는 별도 마이그레이션이 필요하다.
