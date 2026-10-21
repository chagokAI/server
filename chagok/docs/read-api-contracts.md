# 조회 API 응답 계약

공통 경로 W는 `/api/v1/warehouses/{warehouseId}`다. 치수는 mm, 수량은 개, 금액은 VAT 별도 KRW다.
이 문서는 화면 시안과 저장 가능한 데이터에 맞춘 계약이다. 모든 GET은 조회만 하며 배치를 실행하지 않는다.

## 공통 형식

성공 응답은 `{data, meta}`다. 목록 data에는 `items`, `page`(0부터), `size`(기본 20, 최대 100),
`totalElements`, `totalPages`가 있다. 상태 탭은 `counts`, 요약 카드는 `summary`를 추가한다.
인증 없음은 401, 다른 센터 경로는 403, 본인 센터 내 없는 리소스는 404, 잘못된 입력은 공통 오류 형식의 400이다.

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
현재 재고 또는 안전 재고가 없으면 status=UNKNOWN이며 unknownSpecCount에 포함한다.
현재 재고가 누락된 활성 규격이 있으면 totalOnHandQty=null이다. 누락값을 0으로 합산하지 않는다.
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

cost_forecast 저장 모델을 사용한다. 현재 실행기는 OBSERVATION_BASELINE 참고 계산으로 결과를 생성한다.
제안·비용·작업 성공은 같은 jobId로 한 트랜잭션에서 저장한다. 학습 모델 호출은 아직 없다.

## HIST-API-01~02: 발주 이력

`GET W/purchase-orders?from=2026-08-01&to=2026-10-19`

- from/to 필수, 센터 시간대 양 끝 날짜 포함, 최대 5년
- keyword: 발주번호·공급사명 부분 검색. %/_는 문자로 검색
- status: PENDING / APPROVED / IN_TRANSIT / REJECTED / RECEIVED
- sort: createdAt,desc(기본), deliveryDate,asc|desc, totalAmountKrw,asc|desc. 동률은 ID 오름차순
- 항목: id, poNo, createdAt, supplierId, supplierName, boxCodes, specCount, totalQty, totalAmountKrw, deliveryDate, status, version
- counts: ALL·상태별 개수. 기간·검색 적용, 상태 필터 제외
- summary: 동일한 전체 검색 범위의 totalOrderCount, totalAmountKrw, pendingCount, inProgressCount, averageDeliveryLeadTimeDays, receivedSampleCount, incompleteAmountOrderCount

pendingCount는 PENDING, inProgressCount는 APPROVED+IN_TRANSIT이다.
요약 금액은 반려 제외·승인 대기 포함. 반려 외 발주서에 금액 누락이 있으면 totalAmountKrw=null이고 incompleteAmountOrderCount로 알린다. 평균 납품 리드타임은 승인부터 실제 입고까지의 경과 일수이며 유효한 입고 표본만 사용한다.

`GET W/purchase-orders/{purchaseOrderId}`:

발주·제안 ID/상태/각 시각, 납품 희망일, 공급사·결제 조건·납기, 요청자·승인자,
수령 센터·주소·시간·연락처, 결정 사유, 품목·수량·저장 단가·금액·총계·version을 반환한다.
품목이 없거나 수량·단가가 누락된 과거 발주서는 해당 총 수량·금액을 null로 반환한다.
새 발주 품목은 당시 규격 snapshot을 저장한다. 과거 품목의 snapshot이 없으면 현재 표시 정보를 사용하고 표시한다.
공급사·담당자·수령지 정보는 현재 마스터 기준이다.

## NOTI-API-01: 알림

`GET W/notifications?category=INVENTORY&unreadOnly=true`

- category: INVENTORY / PURCHASE / AI / MATCHING, 생략 시 전체
- sort: createdAt,desc(기본) / createdAt,asc
- 항목: id, category, title, message, link, createdAt, readAt, unread
- unreadCount: 본인의 해당 센터 전체 미읽음 수. 카테고리·페이지·unreadOnly와 무관

본인에게 온 알림만 조회하며 다른 센터 소속이면 403이다. 기존 제목 없는 알림은 message를 title에도 제공한다.
CurrentUserProvider는 세션 사용자 ID로 본인을 조회한다. 명시적인 인증 비활성 테스트에서만 기존 첫 사용자 방식을 사용한다.

## NOTI-API-02~03: 알림 읽음 처리

`PATCH W/notifications/{notificationId}/read`

- 응답은 목록 항목과 같은 형식(id, category, title, message, link, createdAt, readAt, unread)이다
- 이미 읽은 알림은 처음 읽은 시각을 유지하고 현재 상태를 그대로 돌려준다. 같은 요청을 반복해도 결과가 같다
- 본인 알림이 아니거나 다른 센터의 알림이면 404다. 존재 여부를 알리지 않기 위해 403이 아닌 404로 응답한다

`POST W/notifications/read-all`

- 본문 `{through?: datetime}`. through를 주면 그 시각 이전에 생성된 미읽음만 처리하고, 생략하거나 본문이 없으면 미읽음 전체가 대상이다
- 목록을 불러온 뒤 도착한 새 알림까지 지우지 않으려면 목록 조회 시각을 through로 보낸다
- updatedCount는 이번 요청으로 바뀐 건수이며 이미 읽은 알림은 세지 않는다. unreadCount는 처리 후 남은 수로 조회와 같은 기준(본인·해당 센터 전체)이다

## JOB-API-01: 작업 상태

`GET W/jobs/{jobId}`:

- id, targetMonth, type, status, createdAt, startedAt, completedAt, dataAsOf
- proposalId, costForecastId, failure{code,message}, retryable, version, trigger, retryOfJobId, retryJobId
- type: PROCUREMENT_AND_COST
- status: PENDING / RUNNING / SUCCEEDED / FAILED / PUBLISH_FAILED / TIMED_OUT
- retryable: FAILED/PUBLISH_FAILED/TIMED_OUT이고 아직 재시도와 연결되지 않았을 때 true
- trigger: MANUAL / SCHEDULED / RETRY (기존 기록은 null일 수 있음)

## JOB-API-02~03: 수동 실행·재시도

`POST W/ai-jobs` — `{ "targetMonth": "2026-11", "type": "PROCUREMENT_AND_COST" }`

- 대상 월은 센터 시간대의 이번 달부터 12개월 뒤까지
- 202 응답의 data는 JobResponse, Location은 `W/jobs/{jobId}`
- 같은 센터·대상 월·작업 종류에 활성 작업이 있으면 기존 작업 반환
- 새로고침의 단순 재조회는 기존 GET, 새 계산 실행은 POST로 구분
- POST 응답은 완료가 아닌 접수 결과. GET 작업 상태가 SUCCEEDED가 되면 latest 결과를 재조회

`POST W/jobs/{jobId}/retry` — `{ "expectedVersion": 2 }`

- 실패·전송 실패·기한 초과 상태에서만 허용. 그 외 상태 또는 버전 충돌은 409
- 원래 실패 기록을 보존하고 새 Job을 생성, retryOfJobId/retryJobId로 연결
- 같은 실패 작업의 재시도 요청이 반복되면 이미 연결한 Job 반환
- 같은 범위의 다른 활성 작업이 있으면 해당 작업 반환

## 정기 실행·DB 대기열

- Spring 기동 중 기본 5분마다 각 센터의 이번 달·다음 달 작업을 같은 접수 서비스로 등록
- 별도 실행기가 기본 1초마다 DB의 PENDING 작업 하나를 실행. 재기동 후에도 대기 작업 유지
- 센터 잠금·활성 작업 유니크 키로 중복 접수를, SKIP LOCKED로 중복 실행을 방지
- RUNNING 상태가 기본 5분을 넘기면 TIMED_OUT. 이전 실행 시도의 결과는 version 검증으로 반영 차단
- 실패하면 마지막 성공 결과 유지. 제안·비용·작업 성공은 원자적으로 반영
- Kafka producer/consumer, 메시지·토픽, DL 호출 로직은 변경하지 않음

현재 참고 계산 방식:

1. 접수 시각 직전 28일 구간의 해당 센터 SUCCEEDED 관측을 박스 규격별 집계
2. 월 참고 수량 = 관측 수 ÷ 28 × 대상 월 일수, 올림
3. 제안 수량 = max(0, 월 참고 수량 + 안전 재고 - 현재 재고), 기본 100개 단위 올림
4. 포장 비용 = 월 참고 수량 × 저장 단가, 발주 비용 = 제안 수량 × 저장 단가

`analysis.method=OBSERVATION_BASELINE`과 입력 기간·표본 수를 저장한다. 실제 박스 소모량·학습 예측 정확도를 의미하지 않는다.
활성 규격이 없거나 전체 성공 관측이 없으면 실패 처리한다. 재고·안전 재고·단가 누락도 실패하며 임의의 0으로 채우지 않는다.
절감액·전월/전년 비교는 근거가 없어 null이다. 재고 차감과 BoxSpec의 실제 소진일 갱신은 수행하지 않는다.

설정: `chagok.jobs.scheduling-enabled`, `worker-enabled`, `cron`, `poll-delay`, `lookback-days`, `order-unit`, `timeout`.
수동 접수·재시도는 OPERATOR/ADMIN, 상태 조회는 VIEWER 이상이며 소속 센터만 접근할 수 있다.

## 검증과 스키마

`./gradlew offlineTest`: DTO·서비스·MockMvc 계약·JPA 쿼리 생성·H2 호환 모드 집계·내장 Kafka 검증.
실제 PostgreSQL·pgvector와 DL 전체 흐름 검증은 PostgreSQL 환경에서 `./gradlew test`로 확인한다.
개발 설정 ddl-auto=update는 inventory_movement와 새 nullable 컬럼·바코드 유니크 제약을 추가한다. 운영에는 별도 마이그레이션이 필요하다.
입고·실사·인증 통합 테스트는 PostgreSQL에서 실행하며 외부 DB 없는 offlineTest에서는 제외한다.

## 상품 수정·매칭 후보

- 상품 목록·상세에 barcode(String, 선행 0 보존), weightG(Integer, g)를 추가한다.
- PATCH 상품은 expectedVersion 필수. barcode는 영문·숫자 1~30자 문자열(선행 0 보존, 영문 대소문자 구분)이고 센터 안에서 중복될 수 없다. 패션 상품처럼 원천 식별자가 영문·숫자 혼합인 경우가 있어 숫자만으로 제한하지 않는다.
- code는 SKU다. DL 상품 조회는 barcode를 사용하며, barcode 없는 기존 행만 code와 비교한다.
- 상품 치수 또는 박스 치수·활성 여부가 바뀌면 연결된 활성 상품의 적합 여부와 비율을 재검증한다.
- 기존 박스가 부적합·비활성이면 박스·비율·수동 사유를 비우고 UNMATCHED로 바꾼다.
- 적합하면 비율을 다시 계산한다. MANUAL은 MATCHED를 유지하고 그 외는 85% 이상 MATCHED, 미만 REVIEW다.
- 상세의 matchCandidates 및 GET products/{id}/box-candidates는 적합한 활성 박스를 충전율 내림차순으로 반환한다.
- 후보: boxSpec, fillRatePct, recommended(첫 후보), selected, source=DIMENSIONS. 회전을 허용한 치수 비교이며 AI 결과가 아니다.
- 이름만 수정하면 매칭 결과를 바꾸지 않는다. 변경된 버전은 다음 요청의 expectedVersion으로 사용한다.
- 상품 치수를 PATCH하면 MANUAL이 아닌 상품은 후보 1순위를 자동 저장한다(match_source=DIMENSIONS, 상태는 85% 기준).
  들어가는 박스가 없으면 UNMATCHED. MANUAL은 박스를 바꾸지 않고 현재 박스만 재검증한다.
- 자동 저장 박스는 같은 시점의 box-candidates 1순위(recommended)와 항상 같다.
- 비스듬한 배치 정책 `chagok.matching.tilted`(기본 enabled=false, clearance-mm=0, 값은 팀 결정 전):
  켜면 90도 회전으로 안 들어가는 박스에 한 평면 기울임 판정(Carver 조건)을 추가하고 후보 source=TILTED로 표시한다.
  TILTED 매칭은 충전율과 관계없이 REVIEW이며 충전율은 부피비 그대로다. 수동 지정(PUT box-match)은 기존 판정만 쓴다.
  시안 박스 9종·상품 7종 기준으로는 기울여야만 들어가는 조합이 없어 결과가 바뀌지 않았다.
- POST product-matches/recalculate: 매칭 박스가 없는 활성 상품에 1순위를 일괄 적용한다. DB에 직접 들어온 상품 채우기용.
  응답 `{targetCount, matchedCount, reviewCount, unmatchedCount}`. 이미 매칭된 상품은 건드리지 않아 반복 호출해도 같다.

## 신규 규격·안전 재고 정책

규격 생성에 unitPriceKrw가 필수다. isActive는 생략 시 true, initialOnHandQty와 safetyQty는 생략 시 0이다.
targetQty는 선택이며 없으면 stockLevelPct=null이다. 새 규격은 alertEnabled=true로 생성한다.
재고 수준은 onHandQty/targetQty×100(최대 100), 주의 판정은 소진일≤공급사 리드타임+여유일이다.
활성 규격·참조·재고·입고/실사 이력이 있으면 삭제할 수 없다.

## 박스 규격 재질·크기 분류·삭제 불가 사유

- material은 코드 A, B, E, AB, BA, BB만 받는다. 생성 시 필수, PATCH는 선택. 그 외 값은 400이며 fieldErrors에 material이 온다.
- 응답은 material(코드)과 materialLabel("골판지 B골")을 함께 준다. DB에 원본 표기("골판지 B골")가 있어도 코드로 바꿔 응답한다.
- 선택지는 `GET /api/v1/box-materials`가 `[{code, label}]`로 준다. 센터와 무관한 고정 목록이다.
- sizeClass는 요청으로 받지 않는다(보내면 400). 내경 부피로 서버가 정한다: S ≤ 10L < M ≤ 50L < L.
  기준은 `chagok.box-size.small-max-liters`, `medium-max-liters`. 내경을 PATCH하면 다시 분류한다.
- 삭제가 막히면 409 REFERENCED_SPEC와 함께 `error.details`를 준다:
  `active`(사용 중 여부), `linkedProductCount`(활성 연결 상품 수), `openPurchaseOrderNos`(PENDING·APPROVED·IN_TRANSIT 발주번호),
  `onHandQty`, `hasHistory`(비활성 상품·완료 발주·제안·이벤트·입고/실사 이력 참조 여부).
- 본문을 해석할 수 없는 400(정의 외 필드, 허용되지 않는 enum 값 등)은 알 수 있으면 원인 필드를 fieldErrors로 준다.
- 공급사·카테고리 목록은 이름 가나다순이다. DB 기본 정렬(en_US.utf8)이 한글을 가나다순으로 정렬하지 않아 서버에서 정렬한다.

PATCH box-inventories/{id}/policy:

```json
{"expectedVersion": 0, "safetyDays": 7, "alertEnabled": true, "targetQty": 2000}
```

safetyQty, safetyDays(1~366), alertEnabled, targetQty 중 하나 이상 필요하다.
safetyQty를 전달하면 해당 수량을 저장한다. 일수만 전달하면 이번 달 저장 제안서의 품목별 월 참고 수량×일수÷월 일수(올림)으로 계산한다.
근거가 없으면 409이며 safetyQty를 직접 입력할 수 있다. 반올림한 일평균 표시값을 계산에 재사용하지 않는다.
안전 재고 수량은 정책 저장 시점의 참고값으로 계산하며 이후 배치에서 자동으로 다시 설정하지 않는다.
응답 dailyUsage는 최신 제안의 소수 4자리 참고 일평균이며 실제 박스 소모량이 아니다.
usageSource=REFERENCE_FORECAST/NOT_AVAILABLE, usageDataAsOf로 기준을 구분한다. 일평균·주간 합계는 동일한 제안 버전을 사용한다.
알림이 켜진 상태에서 안전 재고 미만으로 진입하거나 부족 상태에서 알림을 다시 켜면 센터 사용자의 재고 알림을 저장한다.

## 입고·실사

POST inventory-receipts:

```json
{"requestId":"UUID", "boxSpecId":"UUID", "expectedVersion":0,
 "purchaseOrderId":"UUID 또는 생략", "qty":100, "receivedDate":"2026-10-21", "memo":"검수 완료"}
```

POST inventory-adjustments:

```json
{"requestId":"UUID", "boxSpecId":"UUID", "expectedVersion":0, "onHandQty":125, "reason":"실사 확인"}
```

둘 다 201, data=InventoryMovementResponse(id,requestId,boxSpecId,purchaseOrderId,type,deltaQty,beforeQty,afterQty,occurredOn,memo,createdAt).
입고는 기존 재고에 양수 수량을 더하고, 실사는 확인한 절대 수량(0 이상)으로 바꾼다.
미확인 재고는 먼저 실사해야 하며 최초 실사의 beforeQty·deltaQty는 null이다.
입고일은 센터 시간대의 오늘까지 허용한다. 변경 기록 시각(createdAt·stockUpdatedAt)은 실제 처리 시각이다.
센터 잠금과 version으로 경합을 제어하며 같은 requestId·같은 입력은 기존 결과를 반환한다(중복 증가 없음).
같은 requestId를 다른 내용으로 재사용하거나 버전이 오래되었으면 409다.
발주서 연결은 같은 센터의 APPROVED/IN_TRANSIT이며 해당 규격과 미입고 수량 안에서만 허용한다.
일부만 입고하면 기존 상태를 유지하고, 모든 품목 수량이 채워지면 RECEIVED·receivedAt를 저장한다.
자동 소모 차감은 추가하지 않는다. 실사 조정은 직원이 명시적으로 확인한 변경이다.
GET box-inventories/{id}/movements는 createdAt 내림차순·ID 오름차순 페이지를 반환한다.

## 발주 초안·수정·입고 선택지

POST purchase-orders/from-proposal: {proposalId, requestedDeliveryDate?}

- 양수 제안 수량만 공급사별 PENDING 발주서로 분리하고 당시 규격·저장 단가를 보존한다.
- 새 생성은 201, 같은 제안의 재요청은 기존 DraftGroupResponse를 200으로 반환한다.
- 수량·활성 규격·공급사·저장 단가가 없으면 409다. 수량 전체가 0이어도 발주서를 만들지 않는다.
- 납품일 생략 시 오늘+공급사 리드타임을 사용한다.
- DraftGroupResponse={proposalId, orders:[PurchaseOrderResponse]}다.

PATCH purchase-orders/{id}: {expectedVersion, requestedDeliveryDate?, items?:[{boxSpecId,qty}], status?, decisionReason?}

- PENDING에서만 수량·납품일을 수정한다. 품목은 기존 규격에 한해 일부 수정하며 단가는 서버 저장값을 사용한다.
- 수량 0은 해당 품목 발주 제외를 뜻하며 양수 수량 품목은 하나 이상 필요하다.
- 품목만 수정해도 발주서 version이 올라간다. 서버가 금액을 다시 계산한다.
- PENDING→APPROVED/REJECTED, APPROVED→IN_TRANSIT만 허용한다. 반려 사유는 필수다.
- RECEIVED는 입고 API를 통해서만 전이한다. 승인 시 공급사·규격 활성 여부를 다시 확인한다.
- 승인과 알림 저장은 같은 트랜잭션이다. 공급사에 실제로 전송했다는 의미는 아니다.
- 수령 정보에는 receivingContact를 포함한다.

GET purchase-orders/open?boxSpecId=UUID: APPROVED/IN_TRANSIT 중 미입고 수량이 있는 발주서를 반환한다.
항목은 id,poNo,supplierName,deliveryDate,items[{boxSpec,orderedQty,receivedQty,remainingQty}]다.
기존 POST purchase-orders 직접 승인 경로는 단일 공급사·현재 단가를 검증하고 공통 envelope로 응답한다.
신규 화면은 초안 생성·수정 흐름을 사용한다.
