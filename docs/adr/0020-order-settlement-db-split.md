# ADR 0020 — order ↔ settlement DB 물리 분리 (이벤트 CQRS)

- 상태: Accepted (구현 완료)
- 일자: 2026-06-17

## 컨텍스트

settlement-service 는 order/payment/user/product 데이터가 있어야 정산을 만들고 조회한다. 분리
초기에는 settlement 가 **order 의 공유 DB(opslab)** 에 있는 `orders`/`payments`/`users`/`products`
테이블을 자신의 `@Immutable` JPA 엔티티(`Settlement*ReadModel`)로 **read-only 매핑**해서 읽었다.
코드 의존성은 0 이었지만 — settlement-service `build.gradle.kts` 에
`implementation(project(":order-service"))` 가 없음 — **물리적으로는 한 DB 를 공유**하는 결합이
남아 있었다:

- order 가 `orders`/`payments` 스키마를 바꾸면 settlement 의 read-model 매핑이 깨진다
- 두 서비스가 같은 PostgreSQL 인스턴스·커넥션 풀·max_connections 를 두고 경쟁한다
- "Database-per-Service" 가 reservation/loan 만 충족하고 order↔settlement 는 미달이었다

목표는 settlement 를 opslab 에서 떼어내 **자체 `settlement_db` 를 소유**하는 진짜 DB-per-Service 로
만들되, 두 불변식을 지키는 것이다: (1) 정산 *생성·조회* 핫패스가 order 에 무의존, (2) 서비스 간
**코드·DB 의존성 0** — 어느 서비스도 상대 DB 를 직접 읽지 않는다.

## 결정

opslab 공유 read-model 을 **이벤트 기반 소유 프로젝션(CQRS)** 으로 대체하고, 대사는 order 의 내부
API 호출로 푼다. Strangler 패턴으로 단계적으로 컷오버한다.

### 1. 소유 프로젝션 (settlement_db 의 settlement_*_view)

settlement 가 자체 DB 에 소유하는 프로젝션 테이블을 둔다 — `settlement_payment_view`,
`settlement_order_view`, `settlement_user_view`, `settlement_product_view`. 엔티티는
`adapter/out/readmodel/Settlement*ViewJpaEntity`(일반 `@Entity`, opslab 매핑이 아님)이고,
적재는 order 가 발행한 Kafka 이벤트를 받는 컨슈머가 upsert 로 수행한다
(`adapter/in/kafka/`):

| 컨슈머 | 토픽 | 적재 대상 |
|---|---|---|
| `OrderEventKafkaConsumer` | `lemuel.order.created` | `settlement_order_view` |
| `PaymentEventKafkaConsumer` | `lemuel.payment.captured` | `settlement_payment_view` + 정산 생성 |
| `PaymentRefundedViewConsumer` | `lemuel.payment.refunded` | `settlement_payment_view`(환불액/상태) |
| `ProductEventKafkaConsumer` | product changed | `settlement_product_view` |
| `UserRegisteredEventConsumer` | user registered | `settlement_user_view` |

적재는 `(consumer_group, event_id)` 멱등 + view upsert 라 재발행·재처리에 안전하다.
`PaymentRefundedViewConsumer` 는 별도 그룹(`lemuel-settlement-payment-view`)으로 정산 생성 컨슈머와
분리한다.

### 2. Event-Carried State Transfer (Phase 1 enrich)

정산 *생성* 이 order DB 조회 없이 동작하도록, order 가 `lemuel.payment.captured` 페이로드에 정산에
필요한 데이터를 **동봉**한다: `amount`, `sellerId`, `sellerTier`, `settlementCycle`, `capturedAt`,
`paymentMethod`, `pgTransactionId`, `productName`. `PaymentEventKafkaConsumer` 는 이 동봉값으로
`createSettlementFromPayment(...)` 를 호출한다 — 즉 **정산 생성은 프로젝션 lag 과 무관**하게 이벤트
하나로 완결된다. 이 동봉 페이로드가 서비스 간 계약이 되었고, 이를 강제하는 후속 결정이 ADR 0022 다.

### 3. cross-DB 대사 0 — order /internal/recon

정산↔결제 대사·PG 대사는 order 원천 숫자가 필요하다. 과거엔 settlement 가 opslab 의 order 테이블을
직접 읽었다. 이를 **order 의 내부 API** 로 대체한다:

- order 가 `InternalReconController`(`/internal/recon`)로 자기 합계를 노출한다 — `daily-totals`,
  `period-totals`, `refunds-completed-sum`, `captured-payments`. order 는 자기 opslab 만 읽는다.
- settlement 의 `OrderReconClient` 가 이 API 를 호출해 자기 `settlement_db` 숫자와 비교한다.
- 내부 API 는 공유 시크릿 `X-Internal-Api-Key`(`InternalApiKeyFilter`)로 보호한다 — settlement 가
  헤더로 전송, order 가 검증. gateway 미라우팅 내부 엔드포인트.

→ 양측 모두 자기 DB 만 읽으므로 **cross-DB 연결 0**. 대사는 배치/관리 작업이라 order 일시 장애 시
해당 대사 run 만 실패하면 되고, 정산 생성·조회 핫패스는 여전히 무의존이다. 관측 계층에서는
order 가 `settlement_recon_source_rows/amount`, settlement 가 `settlement_projection_rows/amount`
게이지를 각각 노출하고 Prometheus 에서 건수·금액 드리프트를 대조한다(Phase 5.2 — `SettlementProjectionDrift*`).

### 4. 자체 Flyway 스키마 + datasource

settlement-service 가 자체 Flyway 마이그레이션(`settlement-service/src/main/resources/db/migration/`,
V1 baseline)을 소유해 정산 코어 + 프로젝션 + outbox/processed_events 테이블을 `settlement_db` 에
생성한다. 교차 도메인 FK(`settlements → payments/orders` 등)는 cross-DB 불가라 제거하고 컬럼은
plain BIGINT 로 유지한다. application.yml 의 datasource 는
`${SETTLEMENT_DATASOURCE_URL:jdbc:postgresql://localhost:5432/settlement_db}`, `ddl-auto=validate`
로 자체 스키마를 검증한다.

### 5. 프로젝션 백필

컷오버 시점에 기존 order 데이터가 `settlement_db` 에 없으므로, order 가 기존 행을 이벤트로
**재발행**해 시드한다 — `SettlementProjectionBackfillService.backfillAll()` 이 users/products/
orders/captured-payments 를 각 Publish 포트로 재발행, `SettlementProjectionBackfillController`
(`POST /admin/settlement-projection/backfill`, ADMIN). 발행은 Outbox 에 적재되어 폴러가 Kafka 로
보내고, settlement 컨슈머가 멱등 upsert 한다 — 여러 번 실행해도 안전. 정산 *생성* 경로는
`settlements.payment_id UNIQUE` 가 중복을 막는다.

### Strangler 단계적 마이그레이션

| Phase | 내용 |
|---|---|
| 1 | order 이벤트 페이로드 enrich(셀러 메타 동봉) — 정산 생성이 order 조회 없이 동작 |
| 2 | 이벤트 컨슈머가 소유 프로젝션 적재(read-model 과 dual-run) |
| 3 | 조회/검색/리포트를 read-model → 소유 프로젝션으로 컷오버 |
| 4 | datasource 를 `settlement_db` 로 전환 + 데이터 이관(ETL) + 백필 |
| 5 | 하드닝 — cross-DB 대사(self-totals), 드리프트/lag 관측, opslab 잔여 테이블 정리 |

각 Phase 는 독립 배포·롤백 가능하다. 일회성 운영 절차는 런북으로 분리한다 —
[컷오버](../runbook/settlement-db-cutover.md)(Phase 4),
[프로젝션 lag/드리프트](../runbook/settlement-projection-lag.md)(Phase 5.2/5.6),
[opslab decommission](../runbook/settlement-db-decommission.md)(Phase 5.5).

## 결과

### 좋아지는 점

- 진짜 Database-per-Service — order↔settlement 가 **코드·DB 의존성 0**, 독립 배포/확장
- order 스키마 변경이 settlement 를 깨지 않음(이벤트 계약으로만 결합)
- 정산 생성이 프로젝션 lag·order 가용성과 무관(Event-Carried State Transfer)
- 두 DB 가 커넥션 풀·max_connections 를 더 이상 경쟁하지 않음
- 대사도 cross-DB JOIN 없이 내부 API 합계 비교로 분해 — 분리 불변식 유지

### 트레이드오프 / 리스크

- 프로젝션은 최종 일관성 — 조회/리포트/검색이 lag 만큼 stale 가능(생성은 영향 없음)
- 이벤트 페이로드가 서비스 계약이 되어 무성 드리프트 위험 → 스키마 강제 필요(ADR 0022)
- 운영 데이터 이관(ETL)이 금융 데이터라 최대 난관 — 병렬 운영 + 대사 통과를 게이트로(빅뱅 금지)
- outbox/processed_events 가 양 DB 에 이중 존재 — 누락 시 발행/멱등 깨짐
- 컷오버 전 백필 누락 시 조회 공백 → 순서(배포→백필→트래픽 전환) 엄수

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **opslab 공유 + @Immutable read-model 유지** | ✗ | 코드 의존 0 이나 물리 DB 결합·스키마 결합 잔존 |
| **settlement 가 order DB 직접 read(JDBC)** | ✗ | cross-DB 결합 최악 — DB-per-Service 위반 |
| **이벤트 CQRS 소유 프로젝션 + 내부 API 대사 (본 결정)** | ✓ | 코드·DB 의존 0, 핫패스 무의존, 대사도 cross-DB 0 |
| **빅뱅 컷오버** | ✗ | 금융 데이터 무손실 보장 불가 — Strangler 단계 분리 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0005 — Kafka vs ApplicationEvents](0005-kafka-vs-application-events.md)
- [0012 — Outbox 분산 트레이싱](0012-distributed-tracing-across-outbox.md)
- [0021 — shared-common 을 버전드 플랫폼 라이브러리로](0021-shared-common-as-platform-library.md)
- [0022 — 이벤트 Schema Registry](0022-event-schema-registry.md) (Phase 5.3)
- 런북: [DB 컷오버](../runbook/settlement-db-cutover.md) ·
  [프로젝션 lag/드리프트](../runbook/settlement-projection-lag.md) ·
  [opslab decommission](../runbook/settlement-db-decommission.md)
