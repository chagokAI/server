# Server CI/CD

## 실행 흐름

- main 대상 PR: Java 25 전체 테스트, JAR 빌드, 배포 스크립트 테스트, Docker 컨테이너 기동 검증.
- 테스트용 PostgreSQL(pgvector)·Kafka는 GitHub 러너 안에서 새로 생성한다. 운영 DB·Kafka는 사용하지 않는다.
- main push / Server Deploy 수동 실행: 같은 검증 후 `ghcr.io/chagokai/server`에 amd64·arm64 이미지를 발행한다.
- 저장소 변수 `DEPLOY_ENABLED=true`: 검증한 digest로 기존 `backend`만 교체한다.
- Server Rollback: 직전 성공 backend 이미지로 전환한다.

## 서버 및 GitHub 설정

서버는 로컬 SSH 별칭 `choi`, 실제 접속은 `apple@chagok.cloud:22`이다.
기존 Docker Desktop과 배포용 `/Users/apple/.docker-chagok` 인증을 재사용한다.
이 Docker 설정은 키체인 대신 권한 600의 인증 파일을 사용하며, Compose 플러그인 경로와 기존 Unix 소켓을 지정한다.

server 저장소의 `production` Environment에 다음 값을 등록한다.

| 구분     | 이름                 | 값                                                                 |
| -------- | -------------------- | ------------------------------------------------------------------ |
| Variable | `DEPLOY_HOST`        | `chagok.cloud`                                                     |
| Variable | `DEPLOY_USER`        | `apple`                                                            |
| Variable | `DEPLOY_PORT`        | `22`                                                               |
| Variable | `DEPLOY_CONFIG`      | `/Users/apple/deploy/chagok-migration-20261021/server-deploy.conf` |
| Secret   | `DEPLOY_SSH_KEY`     | 기존 로컬 `~/.ssh/choi.pem`의 내용 전체                            |
| Secret   | `DEPLOY_KNOWN_HOSTS` | 확인된 `chagok.cloud` 호스트 공개 키                               |

`DEPLOY_ENABLED`는 Environment 안이 아닌 저장소 Variables에 등록한다.
초기에는 `false`로 두고 CI·이미지 발행을 확인한 뒤 활성화한다.
라이브 물류 재생을 사용할 때는 같은 저장소 Variables에 `LIVE_FEEDER_ENABLED=true`,
`LIVE_ALLOW_REMOTE_DATABASE=true`를 등록한다. 두 변수는 미등록 시 `false`이며,
Server Deploy가 SSH 배포 스크립트를 거쳐 backend 컨테이너의 환경변수로 전달한다.
`postgres` 같은 Docker 서비스 이름으로 접속하는 DB도 loopback 주소가 아니므로 라이브 재생에는 원격 DB 허용이 필요하다.
기능을 활성화해도 물품 투입은 화면에서 재생을 누를 때 시작한다.
변수 변경만으로 배포가 시작되지는 않는다. main에 push하거나 Server Deploy를 main으로 수동 실행해야 적용된다.
같은 이미지여도 두 변수의 값이 바뀌면 backend를 다시 생성한다.
이미지 발행은 GitHub가 자동 제공하는 `GITHUB_TOKEN`과 해당 작업의 `packages: write` 권한을 사용한다.
server 저장소도 client와 별도로 Secrets 등록이 필요하며 SSH 키 자체는 재사용한다.

`deploy/server-deploy.conf.example`을 실제 서버 설정 경로에 배치한다.
운영 Compose 두 개와 현재 frontend 이미지 override를 그대로 포함한다.
backend 배포는 client의 서버 잠금 파일을 함께 사용하므로 서로 다른 저장소에서 동시에 서비스를 교체하지 않는다.

## 배포 동작

1. 기존 `backend` 한 개와 Compose 프로젝트 이름을 확인한다.
2. 새 digest를 pull하고 해당 아키텍처의 JVM 실행을 검증한다.
3. 운영 postgres 컨테이너에서 `pg_dump --format=custom`으로 일관된 DB 스냅샷을 저장한다. 백업 실패 시 서비스를 교체하지 않는다.
4. 기존 이미지에 로컬 롤백 태그를 붙이고 backend 이미지 override를 원자적으로 갱신한다.
5. `up --no-deps --no-build --pull never --wait backend`로 backend만 교체한다.
6. 컨테이너 health와 기존 frontend를 통한 내부·공개 CSRF API 응답을 확인한다.
7. 실패하면 이전 backend 이미지를 복원하고 배포 실행은 실패로 남긴다.

운영 override는 `DDL_AUTO=validate`를 지정한다. 이 배포 스크립트는 Hibernate의 자동 스키마 변경을 수행하지 않는다.
새 코드가 현재 스키마와 호환되지 않으면 시작이 실패하고 이전 이미지로 복원한다.
복원과 Server Rollback은 되돌릴 이미지가 원래 실행되던 `DDL_AUTO` 값(`previous-ddl-auto`)을 그대로 사용한다.
두 라이브 환경변수도 이전 실행값(`previous-live-env`)으로 함께 복원한다.
validate 이전부터 `update`로 실행되던 이미지도 검증 실패 없이 되돌릴 수 있다.
스키마 변경이 필요한 릴리스는 별도 마이그레이션을 검토한 뒤 배포한다.
이미지 롤백은 DB 데이터를 이전 시점으로 되돌리지 않는다.

## 상태와 수동 조작

- 상태·이미지 override: `/Users/apple/deploy/chagok-migration-20261021/chagok/.server-deploy`
- DB 백업: `/Users/apple/deploy/chagok-migration-20261021/backups/backend-deploy`
- 공통 서버 잠금: 기존 `.client-deploy/deploy.lock`

수동 Compose 작업에도 `frontend.image.yaml`, `backend.image.yaml`을 기본 Compose 뒤에 포함해야 현재 버전을 유지한다.
발표 전 DB를 리허설 전 상태로 되돌리는 스냅숏·복원 절차는 [데모 스냅숏과 복원](demo-snapshot.md)에 있다.
운영 `.env`와 실제 키·토큰·DB 백업은 Git에 포함하지 않는다.
단일 backend 교체 중 짧은 API 중단과 SSE 재접속이 발생할 수 있다.
세션이 메모리에 저장된 구성에서는 backend 교체 후 다시 로그인해야 할 수 있다.

GitHub Actions에서 Server Deploy / Server Rollback의 Run workflow를 main으로 실행할 수 있다.
`history.tsv`, `current-image`, `previous-image`, `previous-ddl-auto`, `previous-live-env`와 로컬 롤백 이미지는 서버에 보존한다.
