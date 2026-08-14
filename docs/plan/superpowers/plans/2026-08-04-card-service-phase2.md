# card-service 2단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `card-service-rules`, `idempotency-and-events`, `event-contract-change`, `tdd-discipline` 스킬을 로드한 뒤 태스크별 체크박스를 순서대로 처리한다.

**Goal:** 1단계(발급·한도·상태·프로젝션)로 완성된 `card-service` 를 **고위드(Gowid)형 법인카드 플랫폼**으로 확장한다.
실시간 승인 → 매입/취소/환불 → 명세서·청구·연체 → 지출관리 SaaS 까지 4단계 AC 를 순차 구현.

**Architecture:** Phase 1 헥사고날 기반 위에서 동일한 ports & adapters 패턴 유지.
신규 어댑터는 `adapter/in/{web,kafka,batch}/` · `adapter/out/{persistence,event}/` 에 위치.
도메인은 Spring 무의존 순수 POJO 유지.

**Tech Stack:** Java 25 / Spring Boot 4.0.4 / PostgreSQL 17 / Flyway (V7~V9) / Kafka / Testcontainers / ArchUnit / JaCoCo

**Status:** 완료 (2026-08-04, branch `feat/card-service-phase2-gowid`)

---

## Global Constraints (Phase 1 상속 + Phase 2 추가)

- **금액은 BigDecimal 강제**, JSON 발행 시 `toPlainString()`(DATA-STANDARD N5).
- **DeclineReason enum 확정 4종**: `LIMIT_EXCEEDED` / `CARD_SUSPENDED` / `MEMBER_INACTIVE` / `MERCHANT_POLICY_VIOLATION` — 추가 금지(ADR 0022 파괴적 변경).
- **이벤트 계약 파괴적 변경 금지**: 기존 스키마(`lemuel.card.authorized`, `lemuel.card.captured`) required 추가·타입 변경은 신규 토픽 버전으로만.
- **Flyway V7 부터 신규 파일**로 추가 (V5·V6 이미 존재, V4 이하 수정 금지).
- **TDD**: 실패 테스트 먼저 → 구현 → 통과. `./gradlew :card-service:test` + JaCoCo LINE ≥ 90%.
- **배치 자기 호출(self-invocation) 안티패턴 금지**: 배치 서비스 내 `@Transactional` private/public 메서드 직접 호출은 Spring 프록시를 우회한다. 건당 독립 트랜잭션이 필요하면 별도 `@Service` 빈(`REQUIRES_NEW`)에 위임.
- **승인 경로 비결합 강제**: `AuthorizeCardService` 가 `ExpenseReport` 등 지출관리 코드를 참조하면 `ExpenseWorkflowDecouplingTest`(ArchUnit) + `AuthorizationLatencyTest`(p99≤300ms) 양쪽에서 실패.

---

## Phase 2 태스크 목록

### AC1 — 실시간 승인(Authorization) + 가맹점/MCC 정책 ✅

- [x] **도메인**: `AuthorizationHold`(ACTIVE/CAPTURED/PARTIALLY_CAPTURED/VOIDED/EXPIRED) · `HoldStatus` · `DeclineReason`(4종 확정) · `MerchantPolicy`(허용/차단 MCC, 1회·일·월 한도, 해외/온라인 토글)
- [x] **가용한도 불변식**: `가용 = masterLimit − Σ활성홀드 − Σ매입액`. `findByIdForUpdate` 비관적 락 **후** 검증.
- [x] **멱등**: `authorizationId` L3 UNIQUE → 기존 홀드 반환.
- [x] **포트·서비스**: `AuthorizeCardUseCase` · `AuthorizeCardService`(비관적 락 패턴, 가맹점 정책 평가 → DeclineReason).
- [x] **어댑터**: VAN(`POST /van/api/v1/authorize`) + 내부(`POST /internal/api/v1/authorize`) 두 엔드포인트.
- [x] **Outbox**: `lemuel.card.authorized` 발행(계약 스키마 선확정 — Phase2ContractPlaceholderTest 통과).
- [x] **Flyway V6**: `authorization_holds` · `merchant_policies` 테이블.
- [x] **통합테스트**: `ConcurrentAuthorizationIT`(동시 승인 경합, 비관적 락 불변식 증명).

### AC2 — 매입(Capture)·부분매입·취소(Void)·환불(Refund)·홀드 만료 ✅

- [x] **도메인**: `CardCapture`(CAPTURED/PARTIALLY_CAPTURED/REFUNDED) — 홀드 소진 로직.
- [x] **매입(Capture)**: `CaptureCardUseCase` · `CaptureCardService` — 홀드 전액/부분 소진, `PARTIALLY_CAPTURED` 상태.
- [x] **취소(Void)**: `VoidHoldUseCase` · `VoidHoldService` — 홀드 `VOIDED` + masterLimit 원복.
- [x] **환불(Refund)**: `RefundCaptureUseCase` · `RefundCaptureService` — `refundId` L3 멱등, masterLimit 원복.
- [x] **홀드 만료 배치**: `HoldExpiryScheduler`(ShedLock `hold-expiry` PT1H) — 미매입 홀드 `EXPIRED`.
- [x] **Outbox**: `lemuel.card.captured` 발행.
- [x] **Flyway V7**: `card_captures` 테이블.
- [x] **통합테스트**: `CaptureIT` · `VoidIT` · `RefundIT` · `HoldExpiryIT`.

### AC3 — 명세서·청구(Statement/Billing)·상환·연체 자동 전이 ✅

- [x] **도메인**: `CardStatement`(OPEN→CLOSED→{PARTIALLY_PAID,PAID,DELINQUENT}→PAID) · `StatementStatus`.
- [x] **명세서 마감**: `CloseStatementUseCase` · `CloseStatementService` · `CloseStatementScheduler`(ShedLock `close-statements`).
- [x] **상환(Payment)**: `PayStatementUseCase` · `PayStatementService` — `paymentId` L3 UNIQUE 멱등, 전액 납부→PAID + Outbox 발행.
- [x] **연체 배치**: `MarkDelinquentStatementsUseCase` · `MarkDelinquentStatementsService` + `DelinquentStatementProcessor`(별도 빈, REQUIRES_NEW — self-invocation 안티패턴 방지).
- [x] **연체 전이**: CardAccount → DELINQUENT, 승인 거절 `DeclineReason.CARD_SUSPENDED`.
- [x] **자동 복구**: 전액 납부 시 `CardAccount.recoverFromDelinquency()` → ACTIVE.
- [x] **이벤트 계약**: `lemuel.card.statement_paid` 신규 토픽(ADR 0022 하위호환, 신규 스키마 + 정본 샘플).
- [x] **Flyway V8**: `card_statements` · `statement_payments` 테이블.
- [x] **통합테스트**: `StatementBillingIT`(5건) · `DelinquencyAuthorizationIT`(5건).

### AC4 — 지출관리 SaaS (고위드 차별화 축) ✅

- [x] **도메인**: `ExpenseReport`(DRAFT→SUBMITTED→{APPROVED,REJECTED}) · `ExpenseReportStatus` · `DepartmentBudget`.
- [x] **경비보고서 자동 생성**: `CreateExpenseReportFromCaptureUseCase` · `CardCapturedExpenseConsumer` — Kafka `lemuel.card.captured` 소비 → DRAFT 생성, `captureId` L3 UNIQUE 멱등.
- [x] **워크플로**: `SubmitExpenseReportUseCase` / `ApproveExpenseReportUseCase` / `RejectExpenseReportUseCase`.
- [x] **예산 소진율**: `QueryBudgetUtilizationUseCase` · `DepartmentBudget` 집계.
- [x] **REST 어댑터**: `ExpenseWorkflowAdapter`(`POST submit/approve/reject`, `GET budgets`).
- [x] **비결합 보증**: `AuthorizeCardService` → `ExpenseReport` 의존 0, `ExpenseWorkflowDecouplingTest`(ArchUnit) + `AuthorizationLatencyTest`(p99≤300ms).
- [x] **Flyway V9**: `expense_reports` · `department_budgets` 테이블.
- [x] **통합테스트**: `ExpenseWorkflowIT`(8건).

---

## 이벤트 계약 (Phase 2 발행 토픽 3종)

| 토픽                         | 스키마 파일                              | 발행 서비스 | 비고                                 |
| ---------------------------- | ---------------------------------------- | ----------- | ------------------------------------ |
| `lemuel.card.authorized`     | `lemuel.card.authorized.schema.json`     | card        | 계약 선확정, Phase 2 발행 구현 완료  |
| `lemuel.card.captured`       | `lemuel.card.captured.schema.json`       | card        | 계약 선확정, Phase 2 발행 구현 완료  |
| `lemuel.card.statement_paid` | `lemuel.card.statement_paid.schema.json` | card        | Phase 2 신규 토픽(ADR 0022 하위호환) |

---

## DB 마이그레이션 (Phase 2 추가분)

| 버전 | 파일                                             | 내용                                        |
| ---- | ------------------------------------------------ | ------------------------------------------- |
| V6   | `V6__authorization_hold_and_merchant_policy.sql` | `authorization_holds` · `merchant_policies` |
| V7   | `V7__card_captures.sql`                          | `card_captures`                             |
| V8   | `V8__card_statements.sql`                        | `card_statements` · `statement_payments`    |
| V9   | `V9__expense_workflow.sql`                       | `expense_reports` · `department_budgets`    |

---

## 게이트 결과 (완료 시점 기준)

- `:card-service:test` — 전건 fail 0, skip 0 (Docker available 환경)
- `:card-service:jacocoTestCoverageVerification` — LINE ≥ 90% GREEN
- `CardArchitectureTest`(ArchUnit) — 헥사고날 경계 위반 0
- `Phase2ContractPlaceholderTest` — 이벤트 계약 스키마 2종 통과
- `ExpenseWorkflowDecouplingTest` — 승인 경로 비결합 ArchUnit 통과
- `AuthorizationLatencyTest` — 승인 p99 ≤ 300ms(목 기반)

---

## 핵심 설계 결정 (학습 메모)

### self-invocation 안티패턴 (DelinquentStatementProcessor)

`MarkDelinquentStatementsService` 가 자신의 `@Transactional` 메서드를 직접 호출하면 Spring 프록시를 우회해
트랜잭션이 적용되지 않는다. 연체 명세서 1건 처리(`REQUIRES_NEW`)가 단일 트랜잭션으로 묶이면
한 건의 실패가 앞서 처리된 명세서를 전부 롤백시킨다(fail-open 무너짐).
`CardAccountRescreener`(Phase 1) 와 동일한 패턴: 처리 단위 로직을 **별도 `@Service` 빈**으로 분리.

### Jackson ObjectMapper 공백 + NUMERIC 소수점

Spring Boot 기본 ObjectMapper 는 JSON 직렬화 시 `: ` 와 같이 콜론 뒤 공백을 추가한다.
PostgreSQL NUMERIC(19,2)는 항상 소수점 2자리 보존 — `200000` 은 `"200000.00"` 으로 반환.
테스트 단언 시 `"\"key\":\"value\""` 형태보다 `contains("\"key\"")` + `contains("value")` 조합이 안정적.

### DeclineReason.CARD_SUSPENDED 이중 용도

정지된 카드(`CardStatus.SUSPENDED`)와 연체 계정(`CardAccountStatus.DELINQUENT`) 모두 `CARD_SUSPENDED` 로 거절.
별도 `DELINQUENT` 코드를 추가하면 ADR 0022 파괴적 변경이므로 확정 4종에서 해결.

### 승인 경로 비결합 패턴

`AuthorizeCardService` 는 지출관리 코드를 전혀 참조하지 않는다.
경비보고서 생성은 `CardCaptured` 이벤트를 사후 소비(`CardCapturedExpenseConsumer`)하는 방식으로 완전 비동기 분리.
이 설계는 ArchUnit 규칙(`ExpenseWorkflowDecouplingTest`)과 지연 회귀(`AuthorizationLatencyTest`)로 기계 강제된다.
