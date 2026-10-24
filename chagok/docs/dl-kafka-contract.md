# DL 분석 Kafka 메시지 계약

Spring과 DL Worker(chagokAI/item-dimension-api)가 주고받는 메시지 형식이다. 양쪽 코드는 이 문서를 기준으로 맞춘다.

## 1. 흐름

```text
Spring 접수 ── 요청 토픽 ──▶ DL Worker(식별·치수) ── 결과 토픽 ──▶ Spring 결과 컨슈머(박스·업무 DB 반영)
```

- Spring은 발행 확인을 받은 뒤 `item_event`를 PENDING으로 저장하고 바로 응답한다. DL을 기다리지 않는다.
- DL Worker는 요청의 이미지 경로에서 사진을 직접 읽는다. 이미지 원본은 메시지에 싣지 않는다.
- 박스 선택과 업무 DB 반영은 Spring만 한다. Worker는 DB에 접근하지 않는다.

| 토픽 | 발행 | 소비(그룹) | 키 | 파티션 |
| --- | --- | --- | --- | --- |
| `chagok.dl.analysis-request.v1` | Spring | DL Worker (`chagok-dl-worker`) | `eventId` | 3 |
| `chagok.dl.analysis-result.v1` | DL Worker | Spring (`chagok-dl-result`) | `eventId` | 3 |

- 두 토픽 모두 Spring이 기동할 때 만든다. 로컬 Kafka는 자동 생성이 꺼져 있으므로 Spring을 먼저 띄운다.
- 값은 UTF-8 JSON이고 필드 이름은 camelCase다. 타입 헤더는 붙이지 않는다.
- 같은 그룹에서만 소비한다. 그룹 이름이 다른 소비자가 요청 토픽을 읽으면 같은 요청이 두 번 처리된다.

## 2. 요청 메시지 (schemaVersion 1)

```json
{
  "schemaVersion": 1,
  "eventId": "2e01e3ae-f194-4a8b-b6c5-8c21070a2149",
  "warehouseId": "2b7424cf-37a9-5119-98d7-a23142cbb251",
  "imageRefs": ["/data/shots/cam1.jpg", "/data/shots/cam2.jpg", "/data/shots/cam3.jpg"]
}
```

| 필드 | 규칙 |
| --- | --- |
| `eventId` | `item_event.event_id`. 결과 메시지의 키와 본문에 그대로 돌려준다 |
| `warehouseId` | 센터 ID. 결과에 그대로 돌려준다 |
| `imageRefs` | cam1, cam2, cam3 순서의 이미지 경로 3개. 1920x1080 원본. Worker가 읽을 수 있는 경로여야 한다 |

## 3. 결과 메시지 (schemaVersion 1)

측정 성공(등록 물품):

```json
{
  "schemaVersion": 1,
  "eventId": "2e01e3ae-f194-4a8b-b6c5-8c21070a2149",
  "warehouseId": "2b7424cf-37a9-5119-98d7-a23142cbb251",
  "outcome": "MEASURED",
  "dlStatus": "known",
  "barcode": "8801052067643",
  "size": {"type": "measured", "widthCm": 28.7, "depthCm": 22.0, "heightCm": 11.2, "volumeCm3": 7071.7},
  "failureReason": null,
  "failureMessage": null,
  "latencyMs": 152,
  "measuredAt": "2026-10-21T01:02:03.456Z"
}
```

처리 실패:

```json
{
  "schemaVersion": 1,
  "eventId": "2e01e3ae-f194-4a8b-b6c5-8c21070a2149",
  "warehouseId": "2b7424cf-37a9-5119-98d7-a23142cbb251",
  "outcome": "FAILED",
  "dlStatus": null,
  "barcode": null,
  "size": null,
  "failureReason": "IMAGE_UNREADABLE",
  "failureMessage": "cam2 이미지를 읽을 수 없다: /data/shots/cam2.jpg",
  "latencyMs": 3,
  "measuredAt": "2026-10-21T01:02:03.456Z"
}
```

| 필드 | 규칙 |
| --- | --- |
| `outcome` | `MEASURED`(DL이 판정을 냈다) / `FAILED`(판정을 내지 못했다) |
| `dlStatus` | MEASURED일 때 `known`(등록 물품) / `unknown`(처음 보는 물품). FAILED면 null |
| `barcode` | `known`이면 필수. 그 밖에는 null |
| `size` | MEASURED일 때 필수. `type`은 `measured`(등록 상품의 라벨 치수) / `estimated`(모델 추정). 치수는 cm, w ≥ d ≥ h |
| `failureReason` | FAILED일 때 필수. 아래 표의 코드 중 하나 |
| `failureMessage` | 사람이 읽는 설명. 로그용이며 Spring이 분기에 쓰지 않는다 |
| `latencyMs` | Worker가 잰 처리 시간(이미지 읽기 + 추론) |
| `measuredAt` | 처리를 마친 시각(UTC, ISO 8601) |

| failureReason | 뜻 | Worker 재시도 |
| --- | --- | --- |
| `INVALID_REQUEST` | 요청이 계약과 다르다(필드 누락, 이미지 수, schemaVersion) | 안 함 |
| `IMAGE_UNREADABLE` | 경로의 파일이 없거나 이미지로 읽히지 않는다 | 안 함 |
| `DL_REJECTED` | 모델이 입력을 거부했다(해상도, 물체 미검출) | 안 함 |
| `DL_UNAVAILABLE` | 그 밖의 예외가 재시도 후에도 계속됐다 | 2초 간격 3회 |

## 4. 처리 규칙

**DL Worker**
- 결과 발행 확인을 받은 뒤에 요청 오프셋을 커밋한다. Worker가 중간에 죽으면 같은 요청을 다시 처리한다(최소 1회 전달).
- `eventId`를 읽을 수 없는 요청은 결과를 보낼 곳이 없으므로 로그만 남기고 넘어간다.

**Spring 결과 컨슈머**
- 신규 `PENDING`을 처리한다. `WAITING`에는 계약에 맞는 known 결과가 다시 오면 포장 배정을 재시도한다. `SUCCEEDED`·`FAILED`의 재전달은 건너뛴다.
- 행이 아직 없으면 0.5초 간격 5회 다시 확인하고, 그래도 없으면 버린다(발행 후 저장 실패한 고아 작업).
- DB 장애 중에는 5초 간격으로 계속 기다린다. 결과를 버리면 작업이 PENDING에 남기 때문이다.
- 반영 규칙:

| 결과 | item_event | 사유(DB·로그) |
| --- | --- | --- |
| MEASURED · known · 센터에 활성 상품 1개 · 포장 가능한 재고 후보 | SUCCEEDED, 실제 선택 박스·충전율 저장, 현재고 -1·사용 원장 저장(충전율·검토 라벨과 무관) | — |
| MEASURED · known · 재고가 있고 규격에 맞는 후보 없음 | WAITING, 상품·측정값 보존, 차감 없음 | `NO_AVAILABLE_BOX` |
| MEASURED · known · 상품 없음 / 둘 이상 | FAILED | `PRODUCT_NOT_FOUND` / `PRODUCT_AMBIGUOUS` |
| MEASURED · unknown | FAILED | `UNKNOWN_ITEM` |
| FAILED | FAILED | 메시지의 `failureReason` |
| 계약과 다른 결과(JSON 오류, 필수 필드 누락, 모르는 코드) | FAILED | `INVALID_RESULT` |

### 실제 포장 배정과 재고 사용

- 같은 센터의 활성 박스 중 확인된 현재고가 1개 이상인 후보만 기존 치수·회전 조건으로 검사하고 충전율 순으로 선택한다. 상품 마스터의 기본 추천·발주 판단은 재고와 독립적으로 유지한다.
- 담당자가 직접 지정한 `MANUAL` 박스는 자동 변경하지 않는다. 그 박스에 재고가 없으면 대기한다.
- 충전율 45% 미만이나 `TILTED` 배치는 상품 마스터에 검토 필요(REVIEW) 라벨로만 남고, 실제 포장과 차감은 그대로 진행한다. 라벨은 사람과 에이전트가 나중에 박스 구성을 개선하려고 보는 표시다.
- 입고·실사와 동일한 센터 잠금 뒤 이벤트를 잠그고 선택·재고 -1·`CONSUMPTION` 원장·성공/측정을 한 트랜잭션에서 반영한다. 원장의 `request_id`는 eventId에서 파생한 고정 UUID이며 `(warehouse_id, request_id)` 고유 제약을 사용한다.
- `WAITING`은 물품 삭제나 포장 완료가 아니다. captures GET으로 상태·사유·측정값을 확인할 수 있다. 입고나 담당자 수정 후 같은 known 결과를 재전달하면 같은 이벤트에서 배정만 재시도할 수 있다. 성공 후 재전달은 추가 사용을 만들지 않는다.
- 도입 전 완료 이벤트는 다시 차감하지 않는다. 같은 사진을 새 captures 요청으로 보내면 별도의 신규 eventId가 생성된다. 원천 물류 ID/HTTP 재접수 중복 방지는 물류 실행 관리의 별도 범위다.

### 발주 참고 수요와 실제 사용의 구분

- 실제 포장 박스와 사용량은 이벤트·사용 원장에 남긴다. 상품의 기본 추천 박스를 일시 품절이나 대체 사용 때문에 변경하지 않는다.
- 발주 참고 계산은 현재 활성 상품의 기본 추천 규격을 우선 사용한다. 성공 건과 측정 완료된 known `WAITING` 건을 유입 수요로 세어, 품절로 포장이 멈춘 규격의 수요가 사라지지 않게 한다. 기본 규격이 없는 과거 성공 건은 기록된 실제 박스로 집계한다.
- 참고 계산의 기본 규격별 수요는 현재 마스터에 따른 추정이며 실제 사용 실적과 다르다. 기본 규격도 실제 박스도 활성 상태로 연결되지 않으면 계산에서 제외된다. 자동 입고나 정기 재계산을 추가한 것은 아니다.

## 5. 아직 정하지 않은 것

- **결과가 오지 않는 작업:** Worker가 멈추면 작업은 PENDING에 남는다. 몇 분 뒤 FAILED로 볼지는 정하지 않았다.
- **이미지 공용 저장소:** 지금 `imageRefs`는 같은 머신의 파일 경로다. Spring과 Worker가 다른 머신에서 돌면 공유 볼륨이나 객체 저장소가 필요하다.
- **처음 보는 물품의 추정 치수:** 지금은 FAILED로 끝난다. 추정 치수로 박스를 추천할지는 정하지 않았다.
- **대기 건의 사용자 조작:** 별도 재시도 버튼/API와 입고 시 자동 재처리는 아직 없다. 현재 증분은 backend 배정·차감·대기 상태와 같은 결과 재전달 처리다.

## 6. 로컬 라이브 표본 재생

선택적인 로컬 입력기는 기측정 표본을 새 `live-` 이벤트로 결과 토픽에 발행한다. Worker·이미지·요청 토픽을 거치지 않고 위 결과 처리·재고 경로를 재사용한다. 확정 후 SSE, 라이브 변경분 초기화와 기본 off/loopback 안전장치는 [라이브 스트림 문서](live-stock-stream.md)를 따른다. 이 기능을 실제 카메라 추론 실행으로 표시하지 않는다.
