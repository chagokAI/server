# 라이브 재고 스트림

이 기능은 이미 측정된 `dl_measurement_sample`을 신규 물류 이벤트로 재생하는 시연이다. 실시간 카메라 촬영이나 DL 추론을 다시 실행하지 않는다. 기존 이미지 접수·Worker 경로와 시뮬레이션·비교 탭은 유지한다.

## 연결과 이벤트

로그인한 센터의 `GET /api/v1/warehouses/{warehouseId}/live-stream`을 EventSource로 구독한다. 같은 출처의 세션 쿠키를 사용한다. 연결 직후 활성 박스의 `snapshot`을 보낸다. 이후 확정된 DL 처리 결과는 `packed`, `waiting`, `failed`, 입고·실사·라이브 초기화는 `snapshot`이다. 트랜잭션이 롤백되면 결과를 전송하지 않는다.

- `snapshot`: `{at, boxes:[{id,code,name,onHandQty,innerWidthMm,innerDepthMm,innerHeightMm,version}], reason}`. `reason`은 `connect`(연결 직후)·`reset`(라이브 초기화)·`stock`(입고·실사)이다. 화면은 `connect`·`reset`에서만 라이브 장면을 비우고 `stock`에서는 재고 숫자만 바꾼다.
- 처리 결과: `{eventId,observedAt,status,reason,product,box,fillRatePct,itemMm}`. product는 `{id,code,name}`, box는 snapshot과 같은 구조이며 연결되지 않으면 null이다. 사용자 이름·사번·비밀번호·사진 경로는 보내지 않는다. 로그아웃·세션 만료 시 같은 세션의 다른 탭 연결도 종료한다.
- `packed.box.onHandQty`는 커밋 후 읽은 현재고다. 동시에 더 진행된 포장이 있으면 그 변경까지 포함할 수 있다. 프런트엔드는 box.version이 낮은 늦은 값으로 현재고를 덮지 않는다.
- 물품 치수는 이벤트의 DL 측정값을 우선 표시하고, 없으면 상품 치수를 사용한다. 박스 선택은 기존 서버 상품 매칭 기준이다.
- 미로그인 401, 다른 센터 403, 없는 센터 404. 센터별 동시 구독 상한(기본 20) 초과는 503 + `Retry-After: 10`. SSE 연결 전 404·503은 본문 없는 응답이다.
- 전송은 센터마다 전용 스레드 하나가 순서대로 한다. 커밋 직후 결과 수신 스레드는 브라우저 쓰기를 기다리지 않으므로, 느린 연결은 그 센터의 화면 전송만 늦춘다.
- 15초마다 연결 유지 주석을 보낸다. 단일 서버 프로세스의 구독자만 관리한다. Last-Event-ID 재전송은 없고, 재연결 snapshot이 현재고를 복구한다. 놓친 애니메이션을 재구성하지 않는다. 커밋 후 결과/재고 조회가 실패하면 연결을 닫아 snapshot 재동기화를 유도한다. HTTP 오류로 EventSource가 CLOSED이면 클라이언트가 10초 뒤 새 연결을 시도한다. 화면 이탈 시 예약 재시도를 취소한다. 일시정지 중에도 결과 관찰은 유지한다.

## 입력과 초기화

입력기는 기능이 켜져 있어도 서버 시작 시 정지한다. 라이브 재생 버튼에서 시작한 센터에 대해서만 측정 시각·run·eventId 순으로 표본을 하나 읽어 새 `live-<UUID>` 결과를 발행한다. 브로커 확인 후 PENDING 행을 저장하고 기존 결과 수신기·포장·재고·사용 원장을 거친다. 표본 끝에 도달하면 해당 센터의 처음으로 돌아간다. 이미지 접근·요청 토픽·DL Worker 호출은 없다. 발행 실패 시 접수 행을 만들지 않고 다음 틱에 같은 표본을 다시 시도한다. 발행 후 저장 실패는 기존 고아 결과 처리 규칙을 따른다.

- `GET /api/v1/warehouses/{warehouseId}/live-input`: `{available,running,pending}`를 조회한다. `available`은 입력기 사용 가능 여부, `running`은 신규 입력 실행 여부, `pending`은 해당 센터의 접수된 `live-` 처리 대기 수다. 읽기 요청이나 SSE 구독은 입력을 시작하지 않는다.
- `PUT` 같은 경로, 본문 `{running:boolean, controlId?:string, ownedOnly?:boolean}`: 입력 시작/정지. `controlId`는 화면 UUID이며 생략하면 세션 기준이다. `ownedOnly=true`인 이탈 정지는 해당 세션·화면이 시작한 실행만 정지한다. 명시 Pause는 센터 입력을 정지한다. 기존 센터 범위 검사·운영자/관리자 권한·CSRF가 필요하다. 입력기 기능이 꺼져 있으면 503이다. 같은 센터의 시작 요청은 실행기를 중복 생성하지 않는다.
- 일시정지는 실행 중인 투입 틱과 동기화하여 새 발행을 차단한다. 이미 접수된 PENDING은 완료될 수 있어 그동안 재고가 변할 수 있다. 화면은 `pending=0`까지 ‘정지 중’을 표시한다. 확인이 지연되거나 실패하면 완료로 표시하지 않고 상태 재확인을 제공한다.
- 화면 이탈은 그 화면이 시작한 입력에 정지를 요청한다. 로그아웃·세션 만료도 해당 세션이 소유한 실행을 정지한다. 실행 상태는 단일 서버 메모리에 있으며 재시작 후 자동으로 복구하지 않는다.

현재 포장 규칙은 재고가 있는 적합 박스 중 최선을 선택하는 것이다. MANUAL 지정은 자동 변경하지 않는다. 충전율 45% 미만과 TILTED의 REVIEW는 상품 라벨이므로 포장을 막지 않는다. 적합 재고가 없으면 WAITING/NO_AVAILABLE_BOX, unknown은 FAILED/UNKNOWN_ITEM이다.

`live-` 이벤트가 WAITING이 되면 같은 결과의 늦은 재전달로 다시 포장하지 않는다. 정지 완료 뒤 중복 결과로 재고가 차감되는 것을 막으며, WAITING 기록은 초기화까지 보존한다. 재생 시 표본은 새 UUID로 신규 접수된다. 일반 이미지 이벤트의 기존 WAITING 재시도 동작은 유지한다.

초기화는 센터 잠금 안에서 `DL packing event=live-` 사용 원장의 건수만큼 각 박스 현재고를 돌려놓고, 그 원장과 해당 센터의 확정된 `live-` 이벤트를 삭제한다. 결과를 기다리는 PENDING 접수 건은 남긴다. 지우면 늦게 도착한 결과가 고아 재시도로 결과 소비를 몇 초씩 멈추기 때문이다. 이 건이 확정되며 생긴 차감은 다음 초기화가 되돌린다. 일반 포장·입고·실사·상품·알림·원본 표본은 보존한다. 전체 DB나 seed DB를 복원하지 않는다. 반복 실행은 추가 수량을 만들지 않는다.

## 설정과 화면

입력기와 초기화는 기본 꺼짐이다. 원격 DB는 `LIVE_ALLOW_REMOTE_DATABASE=true`로 명시적으로 허용해야 한다. 기본값 false에서는 localhost·127.0.0.1·::1만 받는다. 허용한 원격 DB에서도 다중 호스트·잘못된 URL·userinfo·query/fragment는 거부한다. pgjdbc의 PGHOST·host·service·socketFactory 등 접속 재지정을 막기 위해 무해한 옵션도 포함해 URL query는 허용하지 않는다. 초기화 서비스도 호출 시 같은 검사를 수행한다.

| 환경변수 | 기본값 | 의미 |
|---|---|---|
| LIVE_ALLOW_REMOTE_DATABASE | false | 승인된 원격 DB의 라이브 입력·초기화 허용 |
| LIVE_FEEDER_ENABLED | false | 표본 입력 기능 사용 가능. true여도 재생 버튼을 누르기 전에는 정지 |
| LIVE_FEEDER_INTERVAL_MS | 167 | 투입 고정 주기(ms). 목표 약 6건/초; 발행·DB가 느리면 실제 처리량은 낮아질 수 있음 |
| LIVE_RESET_ENABLED | false | 라이브 변경분 주기 초기화 |
| LIVE_RESET_CRON | `0 */30 * * * *` | Asia/Seoul 기본 시간대의 매시 정각·30분 초기화 |

실제 접속정보는 Git에서 제외된 `.env` 또는 실행 환경변수에 보관한다. 승인된 클라우드 DB에는 아래 설정을 사용하고 DB_URL의 호스트·계정·비밀번호는 해당 환경에 지정한다. 비밀번호를 URL이나 공개 파일에 넣지 않는다.

```properties
DB_URL=jdbc:postgresql://TAILSCALE_DB_HOST:15432/chagok
DDL_AUTO=none
SQL_INIT_MODE=never
LIVE_ALLOW_REMOTE_DATABASE=true
LIVE_FEEDER_ENABLED=true
LIVE_RESET_ENABLED=true
LIVE_RESET_CRON=0 */30 * * * *
JOB_SCHEDULING_ENABLED=false
JOB_WORKER_ENABLED=false
BOOTSTRAP_ENABLED=false
```

`TAILSCALE_DB_HOST`는 실제 Tailscale DB 호스트로 바꾼다. 이 설정은 실제 재고를 차감하고 정각·30분에 `live-` 변경분을 되돌린다. 여러 서버 인스턴스를 함께 실행하는 구성은 지원하지 않는다. SQL 초기화를 끄기 전에 필요한 테이블과 vector 확장이 DB에 준비되어 있어야 한다. 일반 입고·실사·포장 데이터는 초기화 대상이 아니다.

3D 라이브 탭의 재생·일시정지는 실제 서버 입력 시작·정지 명령이다. 재생 전에는 신규 입력과 그로 인한 재고 차감이 없다. 서버가 고른 박스·충전율·현재고를 표시하고 대기·실패는 따로 기록한다. 적재는 기존 시뮬레이션의 `Truck.place()`로 계산한다. 2,800×1,600×1,600mm 적재함 안에서 회전·겹침·70% 밑면 지지를 검사하고, 자리를 찾지 못하면 다음 트럭으로 넘긴다. 서버 박스를 다시 고르거나 임의 축소하지 않는다. 빈 트럭에도 못 싣는 규격·잘못된 치수는 「적재 확인 필요」로 따로 표시하며 포장 성공·재고를 되돌리지 않는다.

이 배치는 현재 박스 내경과 고정 차량 치수에 따른 추정이다. 실제 차량 좌표·박스 외경·중량·파손 제약을 반영하거나 최소 차량 대수를 보장하는 최적화는 아니다. 시뮬레이션과 공통 시간 계산을 사용하며 6건/초 기준 약167ms 간격으로 물품을 한 개씩 출발시킨다. 프레임 지연이나 몰려 온 이벤트를 한 프레임에 일괄 출발시키지 않는다. 진행 중인 물품이 모두 내려앉은 뒤 트럭을 교체하며, 교체 중의 입력도 FIFO 대기열에서 순차 표시한다. 모든 연출을 보존하므로 서버 확정값보다 화면 적재가 늦을 수 있고 자동 즉시 적재는 하지 않는다. 최근 처리 기록은 100건, 장면에는 현재 트럭과 완료 트럭 3대를 남긴다. 이전 트럭의 참조·개별 GPU 자원은 정리하고 현재 적재 계획은 유지한다.

대시보드 재고 카드는 진입 시 자동 구독하며 별도 재생 버튼이 없다. 재고 이벤트를 최대 2초에 한 번 `/dashboard` 조회로 병합한다. 재고 영역만 갱신하며 발주·예측 새로고침과 기준 시각을 분리한다. 조회 실패·연결 재시도 중에는 기존 값과 시각을 유지한다. 대시보드 이탈은 연결과 예약된 조회를 정리한다.

## 검증

독립 임시 PostgreSQL/Kafka의 접속정보를 환경변수로 지정하고 `DDL_AUTO=update`, `SQL_INIT_MODE=always`, `LIVE_ALLOW_REMOTE_DATABASE=false`, `LIVE_FEEDER_ENABLED=false`, `LIVE_RESET_ENABLED=false`, `JOB_SCHEDULING_ENABLED=false`, `JOB_WORKER_ENABLED=false`, `BOOTSTRAP_ENABLED=false`를 명시한 뒤 `./gradlew test --rerun-tasks --console=plain`을 실행한다. 클라우드 주소가 설정된 상태로 통합 테스트를 실행하지 않는다. 테스트는 SSE 상태·권한·확정 후 전송·롤백, 입고 snapshot, 재생 전 재고 유지·재생 후 차감·정지 후 신규 입력 차단·접수 대기 수·세션 종료, 요청 토픽 미사용, 초기화·중복·원격 기본 차단/명시적 허용을 확인한다. client는 `node --test tests/*.test.mjs`, `npm run format:check`, `npm run build`를 사용한다. 화면 구동 테스트는 사용자가 수행한다.

Gradle도 `TEST_DB_URL` 또는 환경변수 `DB_URL`의 명시적인 loopback 주소를 요구한다. 대상이 없거나 원격/URL 우회이면 테스트 JVM을 시작하기 전에 거절하며 `.env`의 DB 주소로 대체하지 않는다. 테스트 JVM은 `CHAGOK_ENV_FILE`을 없는 경로로 지정해 `.env` 자체를 읽지 않으므로, 브로커·계정·토큰도 실행 환경변수로만 받는다. `TEST_DB_USERNAME/TEST_DB_PASSWORD` 또는 `DB_USERNAME/DB_PASSWORD`를 함께 지정한다. 테스트 JVM의 라이브 입력·초기화·원격 허용·bootstrap은 기본 비활성화하고, 각 테스트가 필요한 동작만 명시적으로 활성화한다. `offlineTest`는 주소를 생략하면 접속 불가능한 loopback 대체값을 사용한다.
