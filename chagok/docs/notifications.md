# 알림 조회와 실시간 전달

알림은 재고 부족·발주·AI 분석 등의 업무 트랜잭션에서 저장한다. 별도 알림 생성 스케줄러는 없다.
분석 스케줄러는 `JOB_SCHEDULING_ENABLED=true`일 때 기본 5분마다 현재 월·다음 월 작업을 접수하고,
`JOB_WORKER_ENABLED=true`인 실행기가 처리한다. 분석 성공 시 사용자별 AI 알림이 생성된다.
웹 Compose의 스케줄러 기본값은 true이며 `.env`의 명시적인 false는 계속 우선한다.
환경 변경은 실행 중인 backend를 다시 생성해야 반영된다.

라이브 변경분 초기화는 별도 `LIVE_RESET_ENABLED=true`로 활성화한다. Docker DB 서비스명을 사용할 때는
`LIVE_ALLOW_REMOTE_DATABASE=true`도 필요하다. Compose는 `.env`의 값을 전달하고,
GitHub 배포는 같은 이름의 Variables를 SSH 배포 스크립트를 거쳐 전달한다. 자세한 초기화 범위는
[라이브 스트림 안내](live-stock-stream.md)를 따른다.

모든 알림 API의 기준 경로는 `/api/v1/warehouses/{warehouseId}/notifications`이다.

| 요청 | 동작 |
| --- | --- |
| `GET /` | 본인 이력. `category`, `unreadOnly`, 0부터 시작하는 `page`, `size`(최대 100) 지원 |
| `PATCH /{id}/read` | 본인 알림 단건 읽음. 최초 읽은 시각 유지 |
| `POST /read-all` | 본인 알림 전체 읽음. 선택적으로 `{ "through": "ISO 시각" }` 전달 |
| `GET /stream` | 로그인 세션 쿠키를 사용하는 SSE 구독 |

스트림의 `connected` 이벤트는 목록 재조회를 요청한다. `changed`는 신규 알림일 때
`{ "notification": { /* 목록의 Item과 동일 */ } }`, 읽음 변경일 때 `{ "refresh": true }`를 전달한다.
알림 저장·읽음 변경이 커밋된 후에만 이벤트를 발행하며 롤백 시 전달하지 않는다.
15초마다 heartbeat 주석을 보내고 30분 후 연결을 갱신한다. 로그아웃·세션 만료 시 구독을 회수한다.
사용자당 최대 10개 연결이며 센터가 같아도 다른 사용자의 이벤트는 전달하지 않는다.

Nginx는 `notifications/stream`을 SSE 프록시 경로에 포함해 버퍼링을 끈다.
실시간 구독은 서버 인스턴스의 메모리에서 관리한다. 재연결 시 DB 목록을 다시 읽고,
client는 30초 주기 조회도 수행해 다른 인스턴스의 변경이나 라이브 초기화의 삭제를 복구한다.
여러 backend 인스턴스에서 즉시 전달이 필요하면 인스턴스 간 이벤트 전달 계층을 추가해야 한다.
