# 로컬 개발 환경

프로젝트 실행 디렉터리는 `chagok/`(build.gradle·compose.yaml이 있는 디렉터리)다.
Docker Desktop을 실행한 뒤 아래 순서로 시작한다.

## 환경 설정

처음 설정할 때 `.env.example`을 `.env`로 복사하고 `DB_PASSWORD`를 설정한다.
이미 `.env`가 있다면 기존 파일을 사용한다. `.env`는 Git에서 제외된다.

```sh
cp .env.example .env
```

Spring은 `spring.config.import=optional:file:.env[.properties]`로 같은 파일을 읽는다.
별도 셸 export 없이 Compose와 Spring의 DB 주소·계정·비밀번호를 일치시킬 수 있다.
이는 [Spring Boot의 외부 설정 가져오기](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.importing-extensionless)를 사용한다.
IDE에서 실행할 때도 working directory를 이 디렉터리로 지정한다.

| 설정 | 로컬 기본값 |
| --- | --- |
| DB_NAME | chagok |
| DB_PORT | 5432 |
| DB_URL | jdbc:postgresql://localhost:5432/chagok |
| DB_USERNAME | postgres |
| DB_PASSWORD | .env에 저장한 값 |
| KAFKA_PORT | 9092 |
| KAFKA_BOOTSTRAP_SERVERS | localhost:9092 |

DB 포트를 변경하면 DB_PORT와 DB_URL을 함께 수정한다.
Kafka 포트를 변경하면 KAFKA_PORT와 KAFKA_BOOTSTRAP_SERVERS를 함께 수정한다.

## 인프라 실행

```sh
docker compose up -d --wait --wait-timeout 60
docker compose ps
```

macOS에서 docker 명령이나 docker-credential-desktop을 찾지 못하면 현재 터미널의 PATH에 설치 경로를 추가한다.

```sh
export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
```

PostgreSQL과 Kafka는 127.0.0.1에만 노출한다.
postgres-data와 kafka-data 볼륨에 데이터를 보존한다.
일시 정지는 `docker compose stop`, 재개는 `docker compose start`를 사용한다.
기존 볼륨이 있는 상태에서 .env의 DB 비밀번호만 바꾸면 DB 계정 비밀번호가 자동으로 바뀌지 않는다.

## Spring 실행·검증

```sh
./gradlew test bootJar
./gradlew bootRun
```

첫 Spring 기동 시 pgvector 확장을 활성화하고 개발 설정 ddl-auto=update로 테이블을 생성·보완한다.
기본 Spring 주소는 http://localhost:8080 이다.
`GET /actuator/health`로 상태를 확인할 수 있다.
실제 DL 서버가 없어도 전체 테스트는 가짜 DL HTTP 서버와 내장 Kafka를 사용해 분석 흐름을 검증한다.
실제 사진 분석을 실행할 때는 별도로 DL 서버를 띄우고 DL_BASE_URL을 지정해야 한다.

DB 없이 가능한 검증만 실행하려면 `./gradlew offlineTest`를 사용한다.
## 정기 작업과 수동 재계산

Spring 실행 중 기본 5분마다 센터별 이번 달·다음 달 참고 계산을 접수한다.
DB 대기열 실행기는 1초마다 PENDING 작업을 하나 처리한다.
수동 실행은 `POST /api/v1/warehouses/{warehouseId}/ai-jobs`에 targetMonth와 type=PROCUREMENT_AND_COST를 전송한다.
202와 작업 ID를 받은 뒤 GET 작업 상태를 확인하고, 성공 시 latest 결과를 다시 조회한다.

`JOB_SCHEDULING_ENABLED=false`는 정기 접수만 끄므로 수동 실행은 계속 사용할 수 있다.
`JOB_WORKER_ENABLED=false`이면 작업을 접수해도 자동 실행하지 않는다.
`JOB_CRON`으로 정기 주기를 바꿀 수 있다. 현재 로컬 .env에 이 항목이 없어도 기본 활성화된다.

현재 계산은 성공 관측 이력에 기반한 OBSERVATION_BASELINE 참고값이다.
활성 규격·관측 이력·재고·안전 재고·단가가 있어야 성공한다.
실제 소모량·모델 정확도·절감액은 대신 만들어내지 않는다. 상세 계약은 [조회 API 응답 명세](read-api-contracts.md)를 참고한다.
