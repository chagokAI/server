# 프론트·AI 연동 전 Spring 계약

화면에서 쓰는 숫자와 식별자를 목업 표시 문자열로 API에 보내지 않는다.
HTTP 성공 본문은 {data,meta}, 오류는 {error:{code,message,fieldErrors,requestId}}다.
201/202도 동일하며 DELETE·로그아웃의 204는 본문을 파싱하지 않는다.

## 공통 변환

| 화면 | 서버 계약 |
| --- | --- |
| 상품/박스/발주서 id | UUID 문자열. SKU·박스 코드·poNo는 별도 표시 필드 |
| 센터 선택 main/icheon | 로그인 응답 warehouse.id 사용. 현재 스키마는 사용자당 한 센터 |
| category 문자열 | category{id,name} → 이름 표시, 수정·검색은 categoryId |
| supplier 문자열 | supplier{id,name} → 이름 표시, 규격 수정은 supplierId |
| size·dimensions 문자열 | widthMm/depthMm/heightMm 또는 innerWidthMm/innerDepthMm/innerHeightMm로 조합 |
| sizeName, linkedProducts, custom | name, linkedProductCount, isCustom |
| width/depth/height 수정 값 | 상품은 widthMm/depthMm/heightMm, 박스는 innerWidthMm/innerDepthMm/innerHeightMm |
| matchedBoxId·manualReason·weight | boxSpecId·matchOverrideReason·weightG |
| current·safety·level·daysLeft | onHandQty·safetyQty·stockLevelPct·daysToStockout |
| quantity·unitPrice·amount | qty·unitPriceKrw·amountKrw, 포맷 없는 JSON 숫자 |
| 수정·삭제 | 조회 응답 version을 expectedVersion으로 전달. 409면 새로 조회 |
| 화면 1페이지 | 요청 page=0. totalPages·counts·summary는 서버 응답 사용 |
| name/code 검색 | searchBy=NAME/CODE |
| fill-desc/fill-asc/name | sort=fillRatePct,desc / fillRatePct,asc / name,asc |
| ordered-desc/ordered-asc/amount-desc | sort=createdAt,desc / createdAt,asc / totalAmountKrw,desc |
| YYYY.MM.DD 표시 | API 요청 날짜는 YYYY-MM-DD, 대상 월은 YYYY-MM |
| 알림 detail/time/tone | message/createdAt/category를 화면에서 변환. unreadCount는 서버 전체 개수 |

수량·금액은 숫자로 유지하고 렌더링할 때만 콤마·개·₩을 붙인다.
비율은 이미 0~100 기준이므로 다시 100을 곱하지 않는다. null은 미산출/미확인, 0은 실제 0이다.
시각(Instant·OffsetDateTime)은 Asia/Seoul로 표시하고 LocalDate는 날짜 자체를 유지한다.
카테고리·공급사의 이름을 UUID 필드에 보내거나 폼 객체 전체를 그대로 전송하면 400이다.
정수 필드에 소수 JSON 숫자를 보내면 400이다.

## 상태 표시

| 종류 | 서버 값 | 화면 라벨 |
| --- | --- | --- |
| 매칭 | MATCHED / REVIEW / UNMATCHED | 매칭 완료 / 검토 필요 / 미매칭 |
| 재고 | SUFFICIENT / CAUTION / SHORTAGE / UNKNOWN | 충분 / 주의 / 부족 / 미확인 |
| 규격 | isActive true/false | 사용 중 / 미사용 |
| 발주 | PENDING / APPROVED / IN_TRANSIT / RECEIVED / REJECTED | 승인 대기 / 승인 완료 / 배송 중 / 입고 완료 / 반려 |
| 알림 | INVENTORY / PURCHASE / AI / MATCHING | 재고 / 발주 / AI / 매칭 |

상품·규격 저장은 동기 처리한다. 성공 응답이 재계산된 최신 결과이므로 임의로 '처리 중'으로 바꾸지 않는다.
추천 후보 source=DIMENSIONS는 치수 기반 추천이다. 화면에서 AI 추론 결과로 표시하지 않는다.
목업의 충전율 예시는 실제 부피비와 다를 수 있다. 120×80×45mm 상품과 250×180×120mm 박스의 부피비는 8%다.

## 화면별 흐름

- 상품 수정: 상세 조회 → matchCandidates 표시 → PATCH 상품 → 응답·목록 재조회. 부적합한 기존 매칭은 해제된다.
- 규격 등록: 이름·분류·치수·단가·공급사·사용 여부를 전송한다. 생성 초기 재고·안전/목표 수량은 선택이다.
- 안전 재고: safetyDays와 alertEnabled를 저장할 수 있다. 참고 수량이 없으면 safetyQty 직접 입력이 필요하다.
- 재고 수준·상태는 서버 값을 사용한다. 프론트에서 현재/안전 재고 비율로 다시 계산하지 않는다.
- 입고: GET purchase-orders/open?boxSpecId로 UUID와 미입고 수량을 가져오고, POST inventory-receipts를 호출한다.
- 입고·실사 요청의 requestId는 작업 시작 시 한 번 만든다. 네트워크 재시도에는 같은 ID와 내용을 보낸다.
- 박스 주문: latest 제안 조회 → POST purchase-orders/from-proposal → 공급사별 초안 표시 → PATCH 수량·납품일 → PATCH status=APPROVED.
- 공급사가 여러 개면 발주서가 여러 개다. 한 개 공급사 이름을 전체 품목의 공급사로 고정하지 않는다.
- 승인 성공 후 이력으로 이동한다. 쿼리 문자열만으로 승인/공급사 전송 성공을 표시하지 않는다.
- 발주 이력은 from/to 필수. 진행 중 카드는 summary.inProgressCount, 필터 칩은 counts 사용.
- 알림은 서버 unreadCount와 createdAt을 사용한다. 읽음 처리 API 두 개는 직접 구현용으로 남겨두었다.

## 새로고침·참고 계산

단순 화면 갱신은 GET 재조회다. 새 계산은 POST ai-jobs → 202 job.id → GET jobs/{id} 확인 → latest 재조회다.
NOT_GENERATED, RUNNING, FAILED, TIMED_OUT을 각각 표시하고 실패해도 이전 성공 결과는 유지한다.
OBSERVATION_BASELINE은 관측 기반 참고 계산이며 실제 소모량 예측·학습 정확도가 아니다.
목업의 예측 정확도 94.3%, 프로모션 효과, 전월/전년 절감액을 서버 근거 없이 유지하지 않는다.
실제 박스 소모량·절감액·출고 예측·배치 좌표가 없는 경우 빈 상태를 표시한다.

## AI 데이터 경계

Spring은 mm 정수, 현재 AI는 0.1cm 정밀도의 cm 수치를 사용한다. 치수 반영 시 cm×10→mm,
부피 변환 시 cm³×1000→mm³다. 현재 출력 정밀도에는 정수 mm 저장이 가능하다.
AI는 세 변을 내림차순으로 반환하므로 실제 카메라 축 방향이나 3D 자세로 해석하지 않는다.
barcode는 숫자 변환 없이 문자열로 보존하고 SKU와 구분한다.
현재 DL 요청 형식·Kafka 흐름은 유지한다. AI 치수는 아직 카탈로그/관측 치수로 자동 저장하지 않으며 미등록 상품은 기존 UNKNOWN_ITEM 처리다.
이미지 추론·학습 모델 결과를 자동 반영하는 다음 통합 단계에서 이 경계를 연결한다.
