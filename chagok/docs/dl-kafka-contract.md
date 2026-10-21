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
- `item_event`가 PENDING일 때만 반영한다. 같은 결과가 다시 오거나 늦게 온 결과는 건너뛴다.
- 행이 아직 없으면 0.5초 간격 5회 다시 확인하고, 그래도 없으면 버린다(발행 후 저장 실패한 고아 작업).
- DB 장애 중에는 5초 간격으로 계속 기다린다. 결과를 버리면 작업이 PENDING에 남기 때문이다.
- 반영 규칙:

| 결과 | item_event | 사유(로그) |
| --- | --- | --- |
| MEASURED · known · 센터에 활성 상품 1개 | SUCCEEDED, 상품의 매칭 박스·충전율을 옮겨 적음 | — |
| MEASURED · known · 상품 없음 / 둘 이상 | FAILED | `PRODUCT_NOT_FOUND` / `PRODUCT_AMBIGUOUS` |
| MEASURED · unknown | FAILED | `UNKNOWN_ITEM` |
| FAILED | FAILED | 메시지의 `failureReason` |
| 계약과 다른 결과(JSON 오류, 필수 필드 누락, 모르는 코드) | FAILED | `INVALID_RESULT` |

## 5. 아직 정하지 않은 것

- **결과가 오지 않는 작업:** Worker가 멈추면 작업은 PENDING에 남는다. 몇 분 뒤 FAILED로 볼지는 정하지 않았다.
- **이미지 공용 저장소:** 지금 `imageRefs`는 같은 머신의 파일 경로다. Spring과 Worker가 다른 머신에서 돌면 공유 볼륨이나 객체 저장소가 필요하다.
- **처음 보는 물품의 추정 치수:** 지금은 FAILED로 끝난다. 추정 치수로 박스를 추천할지는 정하지 않았다.
