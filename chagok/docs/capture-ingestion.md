# 신규 촬영 접수 연결

기준 업무 데이터와 과거 분석 결과는 `chagok.dump`의 복원 내용을 사용한다.
사진 분석 정확도는 다른 환경에서 검증됐으므로 여기서는 새 촬영의 전송·접수·결과 반영을 검증한다.

## 브라우저 / 카메라 접수

`POST /api/v1/warehouses/{warehouseId}/captures`

- 로그인 세션과 CSRF 헤더를 사용한다. 운영자/관리자만 접수할 수 있다.
- `multipart/form-data`의 `images` 필드를 **cam1, cam2, cam3 순서로 3번** 넣는다.
- JPEG/PNG, 각 12MB 이하, 축소하지 않은 1920×1080 원본이어야 한다.
- 요청 전에 센터 소속과 권한을 확인하고, 파일 형식·크기를 검증한다.
- 이미지 파일 이름은 서버에서 새로 정하므로 원래 파일 이름으로 경로를 지정할 수 없다.

정상 응답은 202이며 `Location`과 `data.eventId`, `data.status=PENDING`, `data.imageCount=3`을 돌려준다.
Kafka 발행 확인을 받지 못하면 503으로 응답하고 접수되지 않은 업로드를 정리한다.
웹의 **박스 관리 → 매칭 → 입고 사진 분석**에서 같은 API를 사용한다.

카메라 수집기가 내부 Docker 네트워크에 있을 때는
`POST http://backend:8080/internal/v1/warehouses/{warehouseId}/captures`에 같은 multipart 요청을 보낸다.
이 경로는 세션 대신 `X-Internal-Token`을 사용하며 frontend에서 프록시하지 않는다.
내부 토큰은 브라우저나 프론트 빌드에 넣지 않는다.

## 저장과 메시지

서버와 Worker는 `capture-images` 볼륨을 `/images`에 함께 마운트한다.
서버만 쓰고 Worker는 읽기 전용으로 사용한다.
새 원본은 `/images/{warehouseId}/{captureUuid}/cam{1,2,3}.{jpg,png}`에 저장한다.
동일한 경로를 요청 메시지의 `imageRefs`에 넣기 때문에 호스트 경로 변환은 필요 없다.
모델/배경/ROI 파일은 기존 `item-dimension-api/app/data` 바인드 마운트를 그대로 사용한다.

Kafka 요청/결과 형식과 토픽 이름은 [기존 계약](dl-kafka-contract.md)을 유지한다.
접수 시 `item_event.image_refs`에 원본 참조를 저장한다.
신규 PENDING 결과를 처리하며, WAITING에는 올바른 known 결과 재전달로 배정을 재시도한다.
종료된 SUCCEEDED·FAILED는 다시 반영하지 않는다. 성공 시 실제 배정 박스의 재고 1개와 사용 원장을 같은 트랜잭션에서 반영한다.
실패 사유는 신규 `failure_reason`, `failure_message` 열에 남긴다. 기존 덤프 행의 새 열은 null이다.
과거 완료 분석을 소급 차감하거나 상품의 기본 매칭을 바꾸는 데이터 시드는 실행하지 않는다.

## 결과 확인

`GET /api/v1/warehouses/{warehouseId}/captures/{eventId}`

`PENDING`(분석 중), `SUCCEEDED`(신규 포장·재고 반영 완료), `WAITING`(미포장 대기), `FAILED`(분석 실패)를 조회한다.
재고가 있는 적합 후보가 없으면 WAITING/NO_AVAILABLE_BOX, 담당자 검토 대상이면 WAITING/REVIEW_REQUIRED이며 차감하지 않는다.
촬영 시각, 이미지 수, 식별 상품·실제 배정 박스·충전율·사유와 저장된 측정값을 함께 조회한다.
WAITING은 물품 삭제가 아니다. 재고 보충·담당자 수정 뒤 같은 known 결과를 재전달하면 배정만 재시도할 수 있다.
별도 사용자 재시도 버튼/API와 입고 시 자동 재처리는 후속 범위다. 센터가 다르면 작업을 조회할 수 없다.
내부 수집기는 `/internal/v1/...`의 같은 조회 경로를 사용할 수 있다.
수집기가 작업 번호를 저장하면 연결이 끊겨도 같은 번호로 다시 상태를 확인할 수 있다.

## 연결 검증

형식/순서/보존/오류 처리 검증:

```sh
./gradlew test --tests '*Capture*Test' --tests '*DlAnalysisRequestTest' --tests '*DlAnalysisResultListenerTest'
```

실제 PostgreSQL + 내장 Kafka 전송 검증:

```sh
./gradlew test --tests '*DlAnalysisFlowIntegrationTest.uploadedCameraImagesReachKafkaAndResultCanBeReadFromTheDatabase'
```

후자는 테스트용 이미지 3장을 HTTP로 업로드하고 Kafka 메시지의 경로에서 원본 바이트를 확인한 뒤,
Worker 계약 형식으로 결과를 보내 상품·박스가 DB와 결과 조회에 반영되는지 확인한다.
이는 분석 정확도 검증을 대신하지 않으며 정확도 평가를 반복하지도 않는다.
실제 모델 Worker까지 포함한 운영 연결 검증은 Docker 엔진이 정상 동작하는 환경에서 별도로 확인한다.

2026-10-21 로컬 통합 환경에서 위 PostgreSQL/Kafka 테스트가 통과했다.
실제 컨테이너 검사도 다음 명령으로 통과했다.

```sh
docker compose up --no-deps photo-check integration-check kafka-check
```

`photo-check`는 연결 확인용 빈 배경 3장을 실제 API로 보내고 실제 Worker가 돌려준
`DL_REJECTED` 결과가 DB/조회 API에 남는 것을 확인했다. 빈 배경의 실패는 예상된 처리 결과다.
이는 새 사진 경로의 연결 검사이며 학습/정확도 검사를 다시 수행한 것이 아니다.
검사로 생성된 촬영 1건은 `.local-data/reports/photo-ingestion.json`의 eventId로 식별한다.

`docker compose up --no-deps db-backup`은 촬영 원본도
`.local-data/cloud-transfer/artifacts/images`로 복사한다. 클라우드 이관 시 이 폴더를 새 서버의
`capture-images` 볼륨에 복사하면 저장된 참조 경로가 유지된다.
