# 엔티티 매핑

`docs/img.png`의 11개 테이블을 `com.skala.chagok.domain.<도메인>.entity`에 매핑한다.

| 도메인 | 엔티티 | 테이블 |
| --- | --- | --- |
| warehouse | Warehouse | warehouse |
| user | AppUser | app_user |
| supplier | Supplier | supplier |
| box | BoxSpec | box_spec |
| product | Product | product |
| procurement | ProcurementProposal | procurement_proposal |
| procurement | ProcurementProposalItem | procurement_proposal_item |
| purchase | PurchaseOrder | purchase_order |
| purchase | PurchaseOrderItem | purchase_order_item |
| event | ItemEvent | item_event |
| notification | Notification | notification |

## 매핑 기준

- 단일 UUID 기본키는 `GenerationType.UUID`로 생성한다.
- 항목 테이블 두 개는 `@EmbeddedId`와 `@MapsId`를 사용한다. 부모와 박스 규격을 먼저 저장한 뒤 항목의 builder에 연관 엔티티를 전달하면 된다.
- `ItemEventId`는 외부 이벤트 식별자인 `eventId`와 발생 시각인 `occurredAt`을 함께 받는다. 자동 생성하지 않는다. PostgreSQL의 타임스탬프 정밀도에 맞춰 발생 시각은 마이크로초 단위로 전달한다.
- 외래키는 단방향 `@ManyToOne(fetch = LAZY)`로 매핑한다. 연관 엔티티를 저장하거나 삭제하는 cascade는 지정하지 않는다.
- `timestamptz`는 `Instant`, `date`는 `LocalDate`, `numeric(5,2)`는 `BigDecimal`, `smallint`는 `Short`를 사용한다.
- JSONB인 `summary`, `placement`는 `Map<String, Object>`로 매핑한다. JSON 내부 속성은 후속 명세에 따라 정의한다.
- 상품 임베딩은 `float[]`와 Hibernate vector 모듈로 `vector(384)`에 매핑한다.
- `Product`, `BoxSpec`, `PurchaseOrder`의 `version`에는 `@Version`을 적용한다.
- `created_at`, `generated_at`은 최초 저장 시 Hibernate가 채운다. 승인·입고·읽음·재고 갱신 시각은 builder에서 전달할 수 있다.
- 엔티티는 getter, builder, protected 기본 생성자를 제공한다. 전체 setter와 연관관계를 포함하는 `equals/hashCode`, `toString`은 생성하지 않는다.

## 후속 명세가 필요한 부분

이미지에는 상태·역할·분류의 허용값, 일반 컬럼의 NOT NULL, UNIQUE, 기본값이 명시되어 있지 않다. 상태·역할·분류는 문자열로 유지하고, 기본키와 복합키 외에 추가 제약이나 기본값을 임의로 만들지 않았다. boolean과 수량도 기본값을 정하지 않아 builder에서 필요한 값을 전달해야 한다.

PostgreSQL 연결 정보와 `ddl-auto: update`는 `application.yaml`에 설정되어 있다. 서버 시작 시 pgvector 확장을 초기화한 뒤 `vector(384)` 컬럼을 포함한 테이블을 자동 생성한다. PostgreSQL 서버에 pgvector 확장 파일이 설치되어 있어야 한다. 접속 정보와 실행 방법은 [database.md](database.md)를 참고한다.

## 검증

`./gradlew test --tests com.skala.chagok.domain.EntityMappingTests`는 외부 데이터베이스 없이 전체 Hibernate 매핑을 초기화하고 PostgreSQL DDL의 JSONB, vector, 복합키 생성을 확인한다. 실제 PostgreSQL에서의 저장·조회 검증은 DB 연결 설정 후 수행해야 한다. 프로젝트 빌드 설정은 JDK 25를 요구한다.
