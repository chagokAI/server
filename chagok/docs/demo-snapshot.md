# 데모 기준 상태 스냅숏과 복원

라이브 초기화는 `live-` 재생이 만든 것만 되돌리므로, 발표 리허설 중에 사람이 만든 발주서나 일반 입고·실사, 그에 따른 재고 변화와 알림은 그대로 남는다. 발표 직전에 DB를 리허설 전과 같은 상태로 돌려놓으려면 그 시점의 DB 전체를 스냅숏으로 떠 두었다가 복원해야 한다.

이 문서는 배포 담당이 운영 서버에서 쓰는 절차로, 대상은 PostgreSQL DB 하나다. Kafka 토픽과 소비 그룹 위치는 되돌리지 않으며, 그래도 되는 까닭과 그 영향은 아래 「Kafka와의 관계」에 정리했다.

## 언제 뜨고 언제 되돌리나

- **스냅숏:** 운영은 `DDL_AUTO=validate`라서 스키마가 다른 스냅숏을 복원하면 backend가 기동하지 못한다. 그러므로 최종 배포를 마치고 데모에 쓸 데이터가 준비된 뒤에 뜨고, 배포로 스키마가 바뀌었다면 그 전에 뜬 스냅숏은 쓰지 않는다.
- **복원:** 복원하는 동안에는 backend가 멈추므로, 리허설이 끝난 뒤 발표 전에 한 번만 한다.

## 1. 스냅숏 뜨기

1. 라이브 입력을 정지한다. 화면의 정지 버튼을 누르거나 `PUT /api/v1/warehouses/{warehouseId}/live-input`에 `{"running": false}`를 보낸다.
2. 결과 대기가 0건인지 확인한다. `GET /api/v1/warehouses/{warehouseId}/live-input`의 `pending`이 0이어야 하고, 센터 전체로 보려면 다음 쿼리가 0을 돌려줘야 한다.

   ```sql
   select count(*) from item_event where status = 'PENDING';
   ```

   스냅숏 뒤에 그 결과들이 소비되어 버리면 복원한 DB의 PENDING 건에는 결과가 다시 오지 않으므로, 0이 아닌 상태로 뜨면 그 건들이 계속 「처리 중」으로 남는다. 결과 그룹 lag은 기준으로 쓰지 않는다. Worker가 트랜잭션을 확정할 때마다 파티션 끝에 표식이 남아서, 처리가 다 끝나도 lag이 0이 아니라 파티션마다 1에서 멈출 수 있다.
3. `pg_dump`는 실행 중인 DB에서도 한 시점의 일관된 사본을 만들므로, backend는 띄워 둔 채로 postgres 컨테이너에서 덤프를 뜬다.

   ```bash
   docker exec <postgres 컨테이너> pg_dump -U <DB 계정> -d chagok --format=custom -f /tmp/demo-baseline.dump
   docker cp <postgres 컨테이너>:/tmp/demo-baseline.dump /Users/apple/deploy/chagok-migration-20261021/backups/demo-baseline.dump
   docker exec <postgres 컨테이너> pg_restore --list /tmp/demo-baseline.dump | wc -l
   ```

   마지막 줄로 덤프가 읽히는지 확인한다. 0줄이 나오거나 오류가 나면 덤프가 깨진 것이다.
4. 복원 후 같은 쿼리로 같은 값이 나오는지 비교할 수 있도록 숫자를 남긴다.

   ```sql
   select (select count(*) from item_event) as events,
          (select count(*) from item_event where status = 'PENDING') as pending,
          (select sum(on_hand_qty) from box_spec) as stock_sum,
          (select count(*) from inventory_movement) as movements,
          (select count(*) from purchase_order) as purchase_orders,
          (select count(*) from notification) as notifications;
   ```

덤프에는 사용자 계정(비밀번호 해시 포함)과 업무 데이터가 그대로 들어 있으므로, 백업 폴더 밖이나 Git, 메신저로 옮기지 않는다.

## 2. 복원하기

1. 라이브 입력을 정지하고 `pending`이 0이 될 때까지 기다린다(스냅숏 1·2단계와 같다).
2. 복원 중에 backend가 DB에 쓰면 복원 결과가 섞이므로 backend를 정지한다.

   ```bash
   docker compose <운영 compose 파일들> stop backend
   ```

   운영 compose 파일 목록은 [CI/CD 안내](cicd.md)의 「상태와 수동 조작」과 같다.
3. DB에 남은 연결이 없는지 확인한다. 0이 나와야 한다.

   ```sql
   select count(*) from pg_stat_activity
   where datname = 'chagok' and pid <> pg_backend_pid() and backend_type = 'client backend';
   ```

4. 복원한다. `--single-transaction`을 주었으므로 도중에 실패해도 DB는 복원 전 상태 그대로이고, `--exit-on-error` 때문에 첫 오류에서 멈춘다.

   ```bash
   docker exec <postgres 컨테이너> pg_restore -U <DB 계정> -d chagok --clean --if-exists --single-transaction --exit-on-error /tmp/demo-baseline.dump
   ```

   덤프 파일이 컨테이너 안에 없으면 먼저 `docker cp`로 넣는다. 종료 코드가 0이 아니면 backend를 띄우지 말고 오류부터 확인한다.
5. 스냅숏 4단계의 쿼리를 다시 돌려 값이 같은지 확인한다.
6. backend를 띄운다. 재기동으로 사용자 세션이 끊기므로 다시 로그인해야 한다.

   ```bash
   docker compose <운영 compose 파일들> up -d --no-deps --wait backend
   ```

## Kafka와의 관계

결과 소비 그룹(`chagok-dl-result`)의 위치는 Kafka가 보관하므로, DB를 되돌려도 소비 위치는 복원과 상관없이 앞으로만 간다. 그래서 DB만 되돌리고 Kafka는 그대로 둔다.

- **스냅숏 뒤에 이미 처리된 결과:** 다시 소비되지 않으므로, 그 결과가 만든 차감·원장은 복원으로 사라진 뒤 다시 생기지 않는다. 의도한 동작이다.
- **backend가 정지된 동안 도착한 결과:** backend를 다시 띄우면 소비된다. 하지만 그 결과의 접수 행은 스냅숏 뒤에 생긴 것이라 복원으로 사라졌으므로, 결과 컨슈머가 행을 찾지 못하고 몇 번 다시 확인한 끝에 데드레터 토픽 `chagok.dl.analysis-result.v1-dlt`로 보낸다. 로그에 `reason=ORPHAN_MESSAGE`가 남고 DB는 바뀌지 않는다.
- 이렇게 데드레터로 간 결과는 대응할 접수 행이 없어 다시 넣어도 또 데드레터로 가므로 재투입하지 않는다. 보존 기간(7일)이 지나면 지워진다. 재투입 절차([DL Kafka 계약](dl-kafka-contract.md) §7.2)는 복원과 관계없는 실제 반영 실패에만 쓴다.
- 요청은 backend만 보내므로 backend가 멈추면 새 요청도 없다. 그래서 DL Worker는 멈추지 않아도 되고, 처리 중이던 요청의 결과는 위 두 번째 경우처럼 데드레터로 간다.

## 단일 브로커의 트랜잭션 설정

DL Worker는 결과 발행과 요청 처리 위치 저장을 한 Kafka 트랜잭션으로 묶는다. 트랜잭션이 쓰는 브로커 내부 토픽 `__transaction_state`의 기본 복제 계수가 3이라, 브로커가 1대뿐이면 이 토픽을 만들지 못하고 Worker가 기동 중 트랜잭션 초기화에서 멈춘다. 운영 Kafka가 단일 브로커라면 브로커 환경변수에 다음 두 값을 넣는다. 로컬 `compose.yaml`과 CI에는 이미 들어 있다.

```yaml
KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
```

`__transaction_state`는 트랜잭션을 처음 쓸 때 만들어지므로, Worker를 처음 띄우기 전에 값을 넣고 브로커를 다시 띄우면 된다. 이 설정은 DB 스냅숏·복원과 관계가 없어서 복원한 뒤에 다시 할 필요는 없다.

## 리허설 기록

2026-10-24에 로컬 실험 스택(PostgreSQL 17 pgvector 컨테이너, Kafka 4.3.1 단일 브로커, 이 브랜치의 backend jar)에서 이 절차대로 한 번 해 봤다. 운영 서버에서는 하지 않았다.

| 단계 | 한 일 | 결과 |
|---|---|---|
| 스냅숏 | PENDING 0에서 backend 실행 중 `pg_dump` | 5.3MB, 목록 127줄 |
| 변경 | 접수 3건을 넣고 결과 3건을 결과 토픽에 발행, backend가 반영 | 이벤트 +3, 재고 합 −3, 원장 +3 |
| 정지 중 결과 | backend 정지 후 접수 1건과 그 결과 발행 | 결과 토픽에 미소비 1건 |
| 복원 | 다른 DB 연결 0 확인 후 위 `pg_restore` 명령 | 종료 코드 0, 약 1초 |
| 재기동 | backend 기동 | 미소비 결과 1건이 `ORPHAN_MESSAGE`로 데드레터 이동, 남은 미소비 결과 0 |

복원 직후와 재기동 후의 숫자는 모두 스냅숏과 같았다.

| 항목 | 스냅숏 | 복원 직후 | 재기동 후 |
|---|---|---|---|
| 이벤트 | 137,768 | 137,768 | 137,768 |
| SUCCEEDED / WAITING / FAILED | 128,946 / 128 / 8,694 | 같음 | 같음 |
| PENDING | 0 | 0 | 0 |
| 센터 재고 합 | 14,941 | 14,941 | 14,941 |
| 사용 원장 | 3,260 | 3,260 | 3,260 |
| 알림 / 제안 / 예측 작업 | 224 / 124 / 120 | 같음 | 같음 |

한계: 실험 DB는 운영 DB의 사본이 아니라 5MB 남짓한 seed 사본이어서, 운영에서는 복원 시간이 더 걸릴 수 있다. 운영 compose의 컨테이너 이름과 DB 계정은 확인하지 못해 자리표시로 남겼다.
