# PostgreSQL 연결 및 테이블 자동 생성

JDK 25와 pgvector가 설치된 PostgreSQL을 사용한다. 기본 JDBC 설정은 다음과 같다.

| 환경변수 | 기본값 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/chagok` |
| `DB_USERNAME` | `postgres` |
| `DB_PASSWORD` | `postgres` |
| `DDL_AUTO` | `update` |

## 실행

접속할 PostgreSQL에 `chagok` 데이터베이스를 먼저 생성한다. JPA는 데이터베이스 내부의 테이블을 생성한다.

```sh
psql -h localhost -p 5432 -U postgres -d postgres -c 'CREATE DATABASE chagok;'
```

환경변수를 설정한 뒤 서버를 실행한다. 기본값을 사용할 때는 `./gradlew bootRun`으로 바로 실행할 수 있다.

```sh
export DB_URL='jdbc:postgresql://localhost:5432/chagok'
export DB_USERNAME='postgres'
export DB_PASSWORD='postgres'
export DDL_AUTO='update'
./gradlew bootRun
```

`.env.example`을 복사해 사용할 수도 있다. Spring Boot는 `.env`를 자동으로 읽지 않으므로 실행할 셸에 명시적으로 불러온다.

```sh
cp .env.example .env
# .env의 접속 정보를 수정한 뒤 실행한다.
set -a
source .env
set +a
./gradlew bootRun
```

## 시작 시 동작

1. JDBC로 지정한 데이터베이스에 연결한다.
2. `db/init/extensions.sql`에서 `CREATE EXTENSION IF NOT EXISTS vector`를 실행한다.
3. Hibernate가 엔티티를 기준으로 테이블 11개와 기본키·외래키를 생성하고, 기존 스키마에 필요한 변경을 적용한다.

`update`는 재시작 시 기존 데이터를 유지한다. 컬럼 이름 변경이나 복잡한 데이터 변환은 별도 마이그레이션이 필요하다. 스키마 관리는 Hibernate가 담당한다.

PostgreSQL 서버에 pgvector 확장 파일이 설치되어 있어야 한다. 확장을 처음 생성할 때 애플리케이션 DB 계정에 필요한 권한이 없다면 관리자가 대상 데이터베이스에서 확장을 먼저 생성한다. DDL 실행에 실패하면 서버 시작도 실패하도록 설정했다.
