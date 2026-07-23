# settlement-service — 구조·기능 조사 문서

> 조사 기준: 2026-07-16 develop 브랜치 코드 실사.
> 관련 정본: [`../SPEC.md`](../SPEC.md) · [`../CLAUDE.md`](../CLAUDE.md) · ADR 0020(이벤트 드리븐 프로젝션) · ADR 0024(이벤트 계약-as-code)

## 1. 개요

이커머스 판매 대금을 **셀러에게 정산**하는 핵심 금융 서비스. 결제 캡처 이벤트를 받아 정산을 생성하고,
등급별 수수료·홀드백을 계산해 확정한 뒤, 복식부기 원장 기록과 출금(Payout)까지 책임진다.

| 항목 | 값 |
|------|-----|
| 포트 | **8082** (관리/Actuator 는 8083 분리) |
| DB | **settlement_db** (PostgreSQL, DB-per-service — order 의 opslab 과 물리 분리) |
| 기동 | standalone 실행가능 jar (`SettlementServiceApplication`) — ADR 0020 Phase 0 에서 order fat-jar 번들 해제 |
| 마이그레이션 | 자체 Flyway (`V1__settlement_baseline.sql` + 타임스탬프 마이그레이션, `validate-on-migrate` 드리프트 차단) |
| 의존 | `shared-common:1.0.0` (composite build) — **order-service 코드 의존 0** |
| 스택 | Spring Boot 4 · JPA + QueryDSL · Spring Batch · Kafka · Elasticsearch(검색, opt-in) · iText(PDF) · Caffeine · Bucket4j · ShedLock |
| 특이점 | Java 21+ 가상 스레드 활성(`spring.threads.virtual`), HikariCP 풀(기본 20)이 실질 동시성 스로틀 |

**MSA 경계 (핵심)**: order/payment/user/product 데이터는 ① Kafka 이벤트를 자체 DB 의
프로젝션 테이블(`settlement_*_view`)에 적재(CQRS)하고, ② 대사(recon)만 order 내부 API
`/internal/recon`(공유 시크릿 `X-Internal-Api-Key`)을 호출해 얻는다. cross-DB 연결 0, 코드 import 0.

## 2. 모듈 구조

`../settlement-service/src/main/java/github/lms/lemuel` 하위 **8개 도메인 모듈 + 2개 지원 패키지** (총 285개 자바 파일):

```
lemuel/
├── settlement/        # 💰 정산 코어 — 생성·확정·수수료·홀드백·역정산·검색·PDF
├── payout/            # 🏧 출금 — 펌뱅킹 지급, 한도, 운영자 retry/cancel, 계좌 PII 암호화
├── ledger/            # 📒 복식부기 원장 — 차대 전표, POSTED 불변, 역분개, 로컬 outbox
├── chargeback/        # ⚠️ 차지백 — PG 분쟁 개시/승인/거절
├── pgreconciliation/  # 🔁 PG 대사 — 파일 업로드 대조, 불일치 승인 → clawback
├── report/            # 📄 캐시플로 리포트 — 일/주/월 버킷 집계 + PDF
├── integrity/         # 🩺 정합성 스위트 — /admin/integrity 6종 점검
├── recon/             # 🔍 order 내부 대사 클라이언트 (OrderReconClient)
├── idempotency/       # 🔐 processed_events 컨슈머 멱등 저장
└── settlement/config/ # 파티션 유지보수 스케줄러 등 서비스 공통 설정
```

각 도메인 모듈은 헥사고날 레이어를 따른다 (ArchUnit 강제):

```
{module}/
├── domain/                          # 순수 POJO + 상태머신 enum (프레임워크 의존 0)
├── application/port/{in,out}/       # UseCase 인터페이스 · 아웃바운드 포트
├── application/service/             # UseCase 구현
└── adapter/
    ├── in/{web, kafka, batch, event, monitoring}/
    └── out/{persistence, readmodel, event, search, pdf, firmbanking, file, recon}/
```

## 3. 기능별 상세

### 3.1 settlement — 정산 코어

**유스케이스** (`settlement/application/port/in/` 9종):

| UseCase | 구현 | 하는 일 |
|---------|------|---------|
| `CreateSettlementFromPaymentUseCase` | `CreateSettlementFromPaymentService` | `lemuel.payment.captured` 수신 → 정산 생성. 셀러 등급별 수수료·홀드백 계산, `settlements.payment_id` UNIQUE 로 멱등 |
| (확정 — Spring Batch) | `adapter/in/batch/confirm/` 4클래스 | 매일 03:00 정산주기(T+N 영업일) 도래 건을 청크(기본 100건/tx) 단위로 REQUESTED→PROCESSING→DONE 확정, `lemuel.settlement.confirmed` 발행 |
| `ReleaseHoldbackUseCase` | `ReleaseHoldbackService` | 홀드백 보유기간 만료 건 해제(매일 03:00) |
| `AdjustSettlementForRefundUseCase` | `AdjustSettlementForRefundService` | `lemuel.payment.refunded` 수신 → 역정산 조정(`settlement_adjustments`), net=0 이면 CANCELED |
| `ApplyLoanDeductionUseCase` | `ApplyLoanDeductionService` | `lemuel.loan.repayment_applied` 수신 → 선정산 대출 상환액을 정산금에서 차감(`settlement_loan_deductions`) |
| `ApplyReconciliationAdjustmentUseCase` | `ApplyReconciliationAdjustmentService` | PG 대사 승인(discrepancy_approved) 수신 → clawback 조정 |
| `ReconcileDailyTotalsUseCase` | `ReconcileDailyTotalsService` | 일일 대사 — 자기 DB 합계 vs order `/internal/recon` self-totals 비교 리포트 |
| `GetSettlementUseCase` / `GenerateSettlementPdfUseCase` / `IndexSettlementUseCase` | `GetSettlementService` 등 | 단건 조회 · 정산서 PDF(iText) · ES 색인(큐 기반, `search.enabled` opt-in) |

**도메인 규칙** (`settlement/domain/`):

- **등급 정책** (`SellerTier.java`) — 수수료율은 정산 시점 `commission_rate` 로 영구 보존:

  | 등급 | 수수료 | 정산주기 | 홀드백 | 해제 |
  |------|--------|---------|--------|------|
  | NORMAL | 3.5% | T+7 영업일 | 30% | 30일 |
  | VIP | 2.5% | T+3 영업일 | 10% | 14일 |
  | STRATEGIC | 2.0% | T+1 영업일 | 0% | — |

- **상태머신** (`SettlementStatus.canTransitionTo`):
  `REQUESTED → PROCESSING → DONE`, `PROCESSING → FAILED → REQUESTED(재시도)`,
  진행 상태(REQUESTED/PROCESSING/FAILED)에서만 `CANCELED` 가능. DONE·CANCELED 는 종결.
- 금액은 전부 `BigDecimal`. 영업일 계산은 `BusinessDayCalculator`, 홀드백은 `HoldbackPolicy`, 주기는 `SettlementCycle`(T+1/3/7).
- 조정은 `SettlementAdjustment`(PENDING→CONFIRMED), 환불·PG clawback·수동 조정이 공통 경로.

### 3.2 payout — 출금

- **스케줄러** (`PayoutScheduler`): 매일 04:00(Asia/Seoul, ShedLock) 확정 정산금 지급 실행. `POST /admin/payouts/execute-now` 로 수동 트리거 가능.
- **펌뱅킹**: `MockFirmBankingAdapter` (mock, `app.firmbanking.failure-rate` 로 실패율 주입 — 운영자 retry 워크플로 시연용).
- **한도**: 시스템 일한도 10억 / 셀러 일한도 1억 (`app.payout.*`).
- **상태머신** (`PayoutStatus.canTransitionTo`): `REQUESTED → SENDING → COMPLETED | FAILED`, `FAILED → REQUESTED(retry) | CANCELED`. **SENDING 중 취소 불허**.
- **계좌 PII**: `SellerBankAccount` 암호화 저장(V20260716200200), `ReencryptPayoutPiiUseCase` + `/admin/payouts/pii/reencrypt` 로 키 회전 재암호화.

### 3.3 ledger — 복식부기 원장

- `LedgerEntry` 는 **차변1·대변1 균형 팩토리**로만 생성(반쪽 전표 금지). 계정: `AccountType` — ACCOUNTS_RECEIVABLE / ACCOUNTS_PAYABLE / REVENUE / COMMISSION_REVENUE / COMMISSION_EXPENSE / SALES_REFUND.
- 전표 유형(`LedgerEntryType`): SETTLEMENT_CREATED · SETTLEMENT_CONFIRMED · REFUND_REVERSED · COMMISSION_RECOGNIZED.
- 상태(`LedgerStatus`): `PENDING → POSTED → REVERSED` — **POSTED 수정 금지, 역분개만**(`ReverseEntryUseCase`). 중복 분개는 UNIQUE 로 차단(V20260715110300).
- **로컬 트랜잭셔널 아웃박스**: 정산 트랜잭션이 `ledger_outbox` 에 태스크를 적재하고, `LedgerOutboxPoller`(5초, ShedLock)가 전표를 비동기 생성 — Kafka 미경유 서비스 내부 아웃박스.

### 3.4 chargeback — 차지백

- `OpenChargebackUseCase`(PG 웹훅/운영자 개시) → `DecideChargebackUseCase`(accept/reject).
- 상태: `OPEN → ACCEPTED | REJECTED`. 사유: FRAUD/DUPLICATE/NOT_RECEIVED/PRODUCT_NOT_AS_DESCRIBED 등.
- 승인 시 정산 조정과 연동(멱등 히트 메트릭 `chargeback.idempotent.hit`).

### 3.5 pgreconciliation — PG 대사

- PG 정산 파일 업로드(`POST /admin/pg-reconciliation/files`, multipart) → 내부 결제 데이터와 대조(run 생성).
- 불일치 유형(`DiscrepancyType`): AMOUNT_MISMATCH / MISSING_INTERNAL / MISSING_PG / DUPLICATE / ROUNDING_DIFF.
- 불일치 승인(`ResolveDiscrepancyUseCase`) 시 Outbox 로 `lemuel.pgreconciliation.discrepancy_approved` 발행(`PgReconciliationOutboxEventAdapter`) → **자기 자신의 컨슈머**(`PgReconciliationApprovedSettlementAdjustConsumer`)가 받아 역정산(clawback) 실행. 승인과 조정을 이벤트로 분리한 saga 형태.

### 3.6 report — 캐시플로 리포트

- `GenerateCashflowReportUseCase` — 일/주/월 버킷(`BucketGranularity`) 현금흐름 집계, 전체/셀러별.
- `CashflowPdfAdapter` (iText) 로 PDF 다운로드 제공.

### 3.7 recon — order 내부 대사 (ADR 0020 Phase 5)

- `recon/OrderReconClient.java` — order 의 `/internal/recon/*` 를 `RestClient` 로 호출(connect 2s/read 5s 타임아웃, `X-Internal-Api-Key` 공유 시크릿). order 는 자기 DB 합계만 노출, settlement 는 자기 settlement_db 숫자와 비교 → **양측 모두 자기 DB 만 읽는 self-totals 대사**. order 장애 시 해당 대사 run 만 `OrderReconUnavailableException` 으로 실패(정산 핫패스 무영향).

### 3.8 integrity — 정합성 스위트

`IntegrityQueryService` 가 6종 점검을 제공 (`/admin/integrity/*`): 원장 완전성(ledger-completeness) ·
payout 대사(payout-recon) · 홀드백 상태(holdback-status) · 멈춘 건(stuck) · 환불 조정 정합(refund-adjustments) ·
처리 건수 대조(processed-count). 환불 원천은 `OrderCompletedRefundsAdapter` 가 recon API 로 조회.

### 3.9 운영 보조

- **DLQ 관리** (`settlement/adapter/in/web/admin/DlqAdminController`): DLT 토픽 inspect / replay (`DlqReplayService`).
- **배치 헬스** (`SettlementBatchHealthIndicator`): 배치 지연을 Actuator health 로 노출.
- **파티션 유지보수** (`config/PartitionMaintenanceScheduler`): 매월 1일 02:30 `audit_logs` 파티션 사전 생성(2028 런웨이).

## 4. REST API

인증: JWT(HS256) + 역할별 인가. `/admin/**` 는 ADMIN 계열, 셀러 리소스는 JWT 주체 파생 + 소유권 대조(IDOR 방지).

| 모듈 | 엔드포인트 | 설명 |
|------|-----------|------|
| settlement | `GET /settlements/{id}` · `GET /settlements/payment/{paymentId}` · `GET /settlements/{id}/pdf` | 단건/결제별 조회, 정산서 PDF |
| settlement | `GET /api/settlements/search` | ES 기반 검색(opt-in) |
| settlement | `GET /api/settlements/query/summary/{daily,monthly}` · `/search` · `/aggregation` · `/approvals` · `/reconciliation` · `/audit/payment/{paymentId}` | QueryDSL 요약·집계·감사 조회 |
| settlement | `GET /admin/reconciliation` | 일일 대사 실행(리포트 반환) |
| settlement | `GET /admin/dlq/inspect` · `POST /admin/dlq/replay` | Kafka DLT 점검·재처리 |
| payout | `GET /admin/payouts/{failed,pending,{id}}` · `POST /admin/payouts/{id}/{retry,cancel}` · `POST /admin/payouts/execute-now` | 출금 운영 |
| payout | `GET /admin/payouts/pii/status` · `POST /admin/payouts/pii/reencrypt` | 계좌 PII 재암호화 |
| ledger | `GET /api/ledger/settlements/{settlementId}` · `/refunds/{refundId}` · `/entries` | 전표 조회 |
| chargeback | `GET /admin/chargebacks/{id}` · `POST /admin/chargebacks/{id}/{accept,reject}` | 차지백 처리 |
| pg-recon | `POST /admin/pg-reconciliation/files` · `GET /runs` · `GET /runs/{runId}` · `POST /discrepancies/{id}/{approve,reject}` | PG 대사 |
| report | `GET /api/reports/cashflow` · `/cashflow/pdf` · `/sellers/{sellerId}/cashflow` | 캐시플로 리포트 |
| integrity | `GET /admin/integrity/{ledger-completeness,payout-recon,holdback-status,stuck,refund-adjustments,processed-count}` | 정합성 6종 |

## 5. Kafka 이벤트

### 5.1 소비 (컨슈머 그룹 `lemuel-settlement`, manual ack, read_committed)

`settlement/adapter/in/kafka/` — 전 컨슈머가 `processed_events` 멱등 + DLT(에러핸들러 `KafkaErrorHandlerConfig`) 공통.

| 토픽 | 컨슈머 | 동작 |
|------|--------|------|
| `lemuel.payment.captured` | `PaymentEventKafkaConsumer` | `settlement_payment_view` 적재 + **정산 생성** |
| `lemuel.payment.refunded` | `PaymentRefundedViewConsumer` / `PaymentRefundedSettlementAdjustConsumer` | 뷰 갱신 / **역정산 조정** (뷰·조정 컨슈머 분리) |
| `lemuel.order.created` | `OrderEventKafkaConsumer` | `settlement_order_view` 적재 |
| `lemuel.user.registered` | `UserRegisteredEventConsumer` | `settlement_user_view` 적재(셀러 등급 근거) |
| `lemuel.product.changed` | `ProductEventKafkaConsumer` | `settlement_product_view` 적재 |
| `lemuel.loan.repayment_applied` | `LoanRepaymentAppliedConsumer` | 선정산 대출 상환 차감 |
| `lemuel.pgreconciliation.discrepancy_approved` | `PgReconciliationApprovedSettlementAdjustConsumer` | PG 대사 clawback 역정산 |

### 5.2 발행 (Transactional Outbox — `outbox_events` INSERT → shared-common 폴러가 Kafka 발행)

| 토픽 | 발행 지점 | 소비처 |
|------|----------|--------|
| `lemuel.settlement.created` | 정산 생성 tx (`SettlementKafkaEventPublisherAdapter`) | loan(선정산 담보) |
| `lemuel.settlement.confirmed` | 정산 확정 tx (동일 어댑터) | loan(상환 트리거) · investment(투자 재원 뷰) · account(GL 분개) |
| `lemuel.pgreconciliation.discrepancy_approved` | 불일치 승인 tx (`PgReconciliationOutboxEventAdapter`) | settlement 자신(clawback) |

이벤트 페이로드는 shared-common `contracts/events/` 의 JSON Schema 와 **양방향 계약 테스트**로 빌드 시점 검증(ADR 0024).

### 5.3 멱등 3단 방어

1. 발행측 — `outbox_events.event_id UUID UNIQUE`
2. 소비측 — `processed_events (consumer_group, event_id)` PK (`idempotency/` 패키지)
3. 도메인 — `settlements.payment_id` UNIQUE 등 자연키 제약

## 6. 배치·스케줄러 (전부 ShedLock 분산락)

| 스케줄 | 클래스 | 주기 | 락 |
|--------|--------|------|-----|
| 정산 확정 | `SettlementScheduler` → Spring Batch `SettlementConfirmJobConfig` | 매일 03:00 | `settlement-confirm-daily` (30m) |
| 홀드백 해제 | `HoldbackReleaseScheduler` | 매일 03:00 | `settlement-holdback-release` (30m) |
| 출금 실행 | `PayoutScheduler` | 매일 04:00 | `settlement-payout-execute` (1h) |
| 원장 아웃박스 | `LedgerOutboxPoller` | 5초 fixedDelay | `ledger-outbox-poller` (5m) |
| 파티션 유지보수 | `PartitionMaintenanceScheduler` | 매월 1일 02:30 | `settlement-partition-ensure-monthly` (10m) |

배치 지표는 `settlement.batch.{creation,confirmation,adjustment}.duration` 히스토그램(p50/p95/p99)으로 노출.

## 7. DB 스키마 (settlement_db, V1 베이스라인 17개 테이블)

| 분류 | 테이블 |
|------|--------|
| 정산 | `settlements` · `settlement_adjustments` · `settlement_loan_deductions` |
| 출금 | `payouts` |
| 원장 | `ledger_entries` · `ledger_outbox` |
| 차지백/PG대사 | `chargebacks` · `pg_reconciliation_runs` · `pg_reconciliation_discrepancies` |
| 프로젝션 뷰 (ADR 0020) | `settlement_order_view` · `settlement_payment_view` · `settlement_user_view` · `settlement_product_view` |
| 이벤트/멱등 | `outbox_events` · `processed_events` |
| 기타 | `audit_logs`(월 파티셔닝) · `settlement_index_queue`(ES 색인 큐) |

이후 마이그레이션 하이라이트: FK·체크 제약과 **불변성 트리거**(V20260715110200 — POSTED 전표 등 수정 차단),
원장 중복 분개 UNIQUE, 금액 컬럼 확폭, outbox 인덱스·보존 함수, `audit_logs` 파티셔닝(+2028 런웨이),
payout 계좌 암호화, PII 가드, 수동 운영 멱등키(V20260719100000).

## 8. 설정·보안·운영

- **필수 환경변수**: `JWT_SECRET`(기본값 없음 — 미설정 시 기동 실패, ≥32바이트), `POSTGRES_USER/PASSWORD`. 운영은 `INTERNAL_API_KEY` + `app.security.internal-key-required=true`(fail-closed).
- **Kafka 스위치**: `APP_KAFKA_ENABLED`(기본 false — 로컬 단독 기동 허용), 컨슈머 동시성 3(파티션 3 상한).
- **ES 검색**: `app.search.enabled=false` 기본 — 꺼져 있으면 `NoOp*Adapter` 로 대체(색인 큐 무시).
- **읽기 복제본**: `app.datasource.read-replica.enabled` opt-in 라우팅 데이터소스.
- **관측**: Prometheus(8083) + OTLP 트레이싱, 프로젝션 lag/applied 메트릭(`settlement.projection.*`), DLT publish/replay 메트릭.
- **DLT**: 컨슈머 실패 시 재시도 후 DLT 발행 → `/admin/dlq/replay` 로 원본 토픽 재발행.

## 9. 테스트

- 구조: 도메인 → 서비스 → 컨트롤러 → 통합 순. `src/test/java` 가 main 과 동일한 모듈 구조(chargeback/idempotency/integrity/ledger/payout/pgreconciliation/recon/report/settlement).
- **통합 테스트**: Testcontainers(PostgreSQL·Kafka·Elasticsearch) — Docker 없으면 skip. 테스트 부팅은 flyway off + create-drop(→ `settlement-integration-test` 스킬).
- **아키텍처 게이트**: ArchUnit 으로 헥사고날 의존 방향 강제.
- **계약 테스트**: shared-common testFixtures 의 JSON Schema·정본 샘플로 프로듀서/컨슈머 양방향 검증.
- **커버리지 게이트**: JaCoCo LINE 90% (`:settlement-service:jacocoTestCoverageVerification`), adapter in/out 서브패키지는 게이트 제외.

```bash
./gradlew :settlement-service:test :settlement-service:jacocoTestCoverageVerification
```
