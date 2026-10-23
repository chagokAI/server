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
실제 DL Worker가 없어도 전체 테스트는 내장 Kafka와 가짜 Worker(요청 토픽을 읽고 결과 토픽에 응답)로 분석 흐름을 검증한다.
실제 사진 분석을 실행할 때는 Spring을 먼저 띄워 요청·결과 토픽을 만든 뒤, chagokAI/item-dimension-api에서 `.venv/bin/python -m app.worker`로 Worker를 띄운다.
메시지 형식과 처리 규칙은 [DL 분석 Kafka 메시지 계약](dl-kafka-contract.md)을 따른다.

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

## 로그인과 최초 로컬 계정

API 인증은 기본 활성화다. 기존 app_user에는 employee_no, BCrypt password_hash, warehouse_id,
role=VIEWER/OPERATOR/DIRECTOR가 필요하다. 계정 등록 API는 제공하지 않는다.
기본 세션은 30분, rememberMe=true는 쿠키·세션 유휴 기간을 7일로 설정한다.
세션 저장소는 현재 서버 메모리이므로 Spring 재기동 시 다시 로그인해야 한다.

빈 로컬 DB에 최초 운영자가 필요하면 Git에서 제외된 .env에 다음 항목을 직접 설정한 뒤 실행한다.

```properties
BOOTSTRAP_ENABLED=true
BOOTSTRAP_EMPLOYEE_ID=local-operator
BOOTSTRAP_PASSWORD=직접_정한_비밀번호
BOOTSTRAP_USER_NAME=로컬 운영자
BOOTSTRAP_WAREHOUSE_NAME=차곡 로컬 센터
```

비밀번호는 8자 이상, UTF-8 72바이트 이하로 정한다. 평문은 DB에 저장하지 않고 BCrypt로 해시한다.
기존 센터를 사용하려면 BOOTSTRAP_WAREHOUSE_ID에 해당 UUID를 지정한다. 생략하면 새 로컬 센터를 만든다.
같은 사번이 이미 있으면 기존 계정·비밀번호·센터를 변경하지 않는다.
첫 생성 후 BOOTSTRAP_ENABLED=false로 되돌린다. 기본값은 false이며 고정 초기 비밀번호는 없다.
이번 구현에서 실제 .env의 계정·비밀번호를 추가하거나 변경하지 않았다.

브라우저 흐름:

1. `GET /api/v1/auth/csrf` → data.headerName과 data.token, 세션 쿠키 받기
2. 해당 헤더와 `{employeeId,password,rememberMe}`로 `POST /api/v1/auth/login`
3. 로그인 후 CSRF 토큰을 새로 조회. 이후 변경 요청에 헤더를 첨부
4. 모든 요청에 `credentials: 'include'` 사용. 사용자/센터는 `GET /api/v1/auth/me`로 조회
5. 같은 토큰 방식으로 `POST /api/v1/auth/logout` → 204

로그인 성공 시 세션 ID와 CSRF 토큰을 교체한다. 쿠키는 HttpOnly·SameSite=Lax다.
HTTPS 배포에서는 SESSION_COOKIE_SECURE=true를 지정한다.
CORS_ALLOWED_ORIGINS 기본값은 localhost:5173과 127.0.0.1:5173이다. 실제 프론트 origin을 쉼표로 지정할 수 있다.
현재 사용자는 하나의 센터에 속하므로 me.warehouses에는 해당 센터 한 개가 들어간다.
다른 센터 접근은 403이며 VIEWER의 업무 변경도 403이다.
AUTH_ENABLED=false는 API 자동 테스트와 명시적인 로컬 진단용으로만 사용한다.
이 모드에서는 인증·권한·CSRF 검사를 끄고 인증 엔드포인트를 등록하지 않는다.

세션 저장·해제는 [Spring Security 공식 세션 관리](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html),
토큰 검증은 [CSRF 문서](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)의 구성에 따른다.

## 화면 연동 전 확인

신규 규격의 단가를 입력하고 초기 재고·안전 재고를 확인한다. 생략한 초기 재고·안전 재고는 새 규격에 한해 0이다.
기존 재고가 null인 규격은 미확인 상태이며 입고 전에 실사 조정해야 한다.
공급사별 초안 생성에는 공급사와 저장 제안 단가가 필요하다.
기존 데이터를 위한 공급사 입력/등록은 이번에 남겨둔 공급사 조회 API와 별개이며 관리 데이터 준비가 필요하다.
프론트 필드 변환·상태 표시 규칙은 [연동 계약](frontend-integration.md)을 참고한다.
