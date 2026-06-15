# 선정산 대출 loan-service Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 정산 인프라 위에 선정산 대출(정산예정금 담보 조기지급) 도메인을 자체 DB로 완전 분리한 `loan-service` 마이크로서비스로 추가한다.

**Architecture:** 헥사고날(Ports & Adapters) + DB-per-service. loan-service는 자체 DB(`lemuel_loan`)를 소유하고, 정산 데이터는 read-model이 아닌 Kafka 이벤트로만 수신해 로컬 뷰(`seller_settlement_view`)로 materialize 한다. 상환은 `SettlementConfirmed` → loan 차감 → `LoanRepaymentApplied` → settlement net payout 의 코레오그래피 saga. Outbox+Kafka·멱등 3단·복식부기 원장은 기존 `shared-common`/`settlement-service` 패턴을 재사용한다.

**Tech Stack:** Java 25, Spring Boot 4, Gradle 멀티모듈(Kotlin DSL), PostgreSQL 17, Flyway, Kafka(Redpanda), Spring Data JPA, QueryDSL, JUnit5 + Mockito + ArchUnit + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-06-15-loan-service-design.md`

---

## 참조 패턴 (기존 코드 — 그대로 미러링)

| 목적 | 참조 파일 |
|------|-----------|
| 멱등 Kafka 컨슈머 | `settlement-service/.../settlement/adapter/in/kafka/PaymentEventKafkaConsumer.java` |
| Outbox 발행 포트 | `shared-common/.../common/outbox/application/port/out/SaveOutboxEventPort.java`, `OutboxEvent.java` |
| Outbox 폴러/Kafka 발행 | `shared-common/.../common/outbox/application/service/OutboxPublisherScheduler.java`, `adapter/out/event/KafkaOutboxPublisher.java` |
| 멱등 처리기록 | `shared-common/.../common/outbox/adapter/in/kafka/ProcessedEventJpaEntity.java`, `ProcessedEventRepository.java` |
| 모듈 build.gradle.kts | `settlement-service/build.gradle.kts` (QueryDSL/Flyway/Kafka 블록 복사, **bootJar 비활성 동일**) |
| 모듈 application.yml | `settlement-service/src/main/resources/application.yml` (단, **datasource URL·Flyway 는 변경**) |
| 헥사고날 ArchUnit | `settlement-service/src/test/.../**ArchitectureTest*` (있으면 미러링) |

> **DRY 원칙**: 스캐폴딩/보일러플레이트(엔티티 매핑, 컨슈머 골격, Outbox 발행)는 위 파일을 복사·치환한다. 본 계획은 **도메인 로직과 saga·멱등의 비자명한 부분만 전체 코드**를 싣는다.

---

## File Structure

### 신규 모듈 `loan-service`
```
loan-service/
├── build.gradle.kts                         # settlement-service 복사, datasource/Flyway 변경
└── src/main/
    ├── resources/
    │   ├── application.yml                   # 자체 DB(lemuel_loan), Flyway 활성
    │   └── db/migration/
    │       ├── V1__loan_core.sql             # loan_advances, loan_repayments, repayment_schedules
    │       ├── V2__loan_ledger.sql           # loan_ledger_entries
    │       ├── V3__seller_settlement_view.sql
    │       └── V4__outbox_processed_events.sql  # 자체 DB용 outbox_events + processed_events
    └── java/github/lms/lemuel/loan/
        ├── domain/
        │   ├── LoanAdvance.java              # 애그리거트 루트 (상태머신·차감 로직)
        │   ├── LoanStatus.java
        │   ├── Money.java                    # (없으면) 금액 VO — 기존 공용 있으면 재사용
        │   ├── CreditAssessment.java         # 한도/수수료 산정 결과 VO
        │   ├── LoanLedgerEntry.java
        │   └── SellerSettlementView.java     # 로컬 정산 뷰 도메인
        ├── application/
        │   ├── port/in/
        │   │   ├── RequestLoanUseCase.java
        │   │   ├── DisburseLoanUseCase.java
        │   │   ├── ApplyRepaymentUseCase.java
        │   │   └── IngestSettlementUseCase.java
        │   ├── port/out/
        │   │   ├── LoadLoanPort.java / SaveLoanPort.java
        │   │   ├── LoadSettlementViewPort.java / SaveSettlementViewPort.java
        │   │   ├── AppendLedgerPort.java
        │   │   └── PublishLoanEventPort.java
        │   └── service/
        │       ├── CreditPolicy.java         # 한도/수수료 정책 (확장 지점)
        │       ├── RequestLoanService.java
        │       ├── DisburseLoanService.java
        │       ├── ApplyRepaymentService.java
        │       └── IngestSettlementService.java
        └── adapter/
            ├── in/web/LoanController.java + dto
            ├── in/kafka/
            │   ├── SettlementCreatedConsumer.java
            │   └── SettlementConfirmedConsumer.java
            ├── out/persistence/             # *JpaEntity, *Repository, *PersistenceAdapter
            └── out/event/LoanEventPublisherAdapter.java   # SaveOutboxEventPort 위임
```

### `settlement-service` 변경 (saga 상대편)
```
settlement-service/.../settlement/
├── application/service/ConfirmDailySettlementsService.java   # Modify: SettlementConfirmed Outbox 발행 추가
├── application/service/CreateSettlementFromPaymentService.java # Modify: SettlementCreated Outbox 발행 추가
├── adapter/in/kafka/LoanRepaymentAppliedConsumer.java         # Create: net payout 트리거
├── application/service/PayoutHoldScheduler.java               # Create: loan 미응답 HOLD 스캐너
└── (payout 도메인) net 지급 로직                              # Modify: amount - deducted
```
> settlement-service 마이그레이션은 order-service(단일 DB)가 소유 → settlement 측 신규 컬럼(`payout_status`)은 order-service `db/migration` 에 timestamp 마이그레이션으로 추가.

---

## Chunk 1: 모듈 스캐폴딩 + 자체 DB 부트스트랩

**목표:** loan-service 가 자체 DB(`lemuel_loan`)로 컴파일·기동되고, ArchUnit 경계 테스트가 통과한다.

### Task 1.1: Gradle 모듈 선언

**Files:**
- Modify: `settings.gradle.kts`
- Create: `loan-service/build.gradle.kts`

- [ ] **Step 1:** `settings.gradle.kts` `include(...)` 에 `"loan-service",` 추가.
- [ ] **Step 2:** `settlement-service/build.gradle.kts` 를 `loan-service/build.gradle.kts` 로 복사. ES/Batch 의존성은 제거(loan은 불필요), Kafka·QueryDSL·Flyway·iText(미사용시 제거)·Testcontainers 블록 유지. **`bootJar` 비활성 동일**(library-mode).
- [ ] **Step 3:** 컴파일 확인.
  Run: `./gradlew :loan-service:compileJava`
  Expected: BUILD SUCCESSFUL (소스 없음, 빈 모듈)
- [ ] **Step 4: Commit** — `git commit -m "build(loan): loan-service 모듈 스캐폴딩"`

### Task 1.2: 자체 DB application.yml + Flyway 마이그레이션 골격

**Files:**
- Create: `loan-service/src/main/resources/application.yml`
- Create: `loan-service/src/main/resources/db/migration/V4__outbox_processed_events.sql`

- [ ] **Step 1:** `application.yml` 작성 — settlement 의 yml 기반, 단:
  - `spring.application.name: lemuel-loan`
  - `datasource.url: jdbc:postgresql://localhost:5432/lemuel_loan?reWriteBatchedInserts=true`
  - `hikari.pool-name: lemuel-loan-pool`
  - `jpa.properties.hibernate.default_schema: public` (또는 `lemuel_loan` 스키마 — DB 분리이므로 별도 인스턴스/DB 권장)
  - `flyway.enabled: true` (★ settlement 와 달리 자체 마이그레이션 소유)
  - ES/Batch 설정 제거.
- [ ] **Step 2:** `V4__outbox_processed_events.sql` — 자체 DB용 `outbox_events`(event_id UUID UNIQUE 포함)·`processed_events`(PK(consumer_group,event_id)) 테이블 생성. order-service 의 동명 마이그레이션 DDL 을 참조해 동일 스키마로 작성. (shared-common outbox 코드가 이 테이블에 매핑됨)
- [ ] **Step 3: Commit** — `git commit -m "build(loan): 자체 DB datasource + outbox/processed_events 마이그레이션"`

### Task 1.3: 테스트 부트스트랩 + ArchUnit 경계 테스트 (TDD 첫 가드)

**Files:**
- Create: `loan-service/src/test/java/github/lms/lemuel/loan/LoanArchitectureTest.java`
- Create: `loan-service/src/test/resources/application-test.yml`

- [ ] **Step 1: 실패 테스트 작성** — ArchUnit 규칙:
  - `loan.domain` 은 `application`/`adapter` 에 의존하지 않는다.
  - `loan..` 은 `github.lms.lemuel.order..`, `github.lms.lemuel.settlement..` 패키지에 의존하지 않는다(코드 경계 0).
  - 헥사고날 레이어 방향(adapter→application→domain).
- [ ] **Step 2:** Run `./gradlew :loan-service:test --tests "*LoanArchitectureTest"` → 클래스 없어 컴파일 단계 통과/규칙은 빈 패키지라 trivially pass. (가드는 이후 코드 추가 시 작동)
- [ ] **Step 3: Commit** — `git commit -m "test(loan): ArchUnit 헥사고날·MSA 경계 가드"`

> **Chunk 1 검토 게이트**: plan/코드 리뷰 후 다음 청크.

---

## Chunk 2: 도메인 — 한도·수수료 산정 (순수 POJO, TDD)

**목표:** 외부 의존 0 인 도메인 계산 로직을 테스트 우선으로 확정한다.

### Task 2.1: Money VO (공용 없을 때만)

- [ ] 기존 공용 Money/금액 VO 존재 여부 확인(`Grep "class Money"`). 있으면 재사용하고 이 태스크 스킵. 없으면 `BigDecimal` 래퍼 VO(불변, 음수 방지, add/subtract/min) 를 TDD 로 작성.

### Task 2.2: CreditPolicy — 한도·수수료 계산

**Files:**
- Create: `loan-service/src/main/java/github/lms/lemuel/loan/application/service/CreditPolicy.java`
- Test: `loan-service/src/test/java/github/lms/lemuel/loan/application/service/CreditPolicyTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
class CreditPolicyTest {
    private final CreditPolicy policy = new CreditPolicy(
            new BigDecimal("0.80"),          // LTV 80%
            new BigDecimal("0.0002"));        // 일할이율 0.02%/day

    @Test
    void 한도는_미지급정산예정금_합계의_LTV() {
        BigDecimal limit = policy.creditLimit(new BigDecimal("1000000"));
        assertThat(limit).isEqualByComparingTo("800000"); // 100만 × 80%
    }

    @Test
    void 수수료는_선지급액_일할이율_일수() {
        BigDecimal fee = policy.fee(new BigDecimal("800000"), 5); // 5일
        assertThat(fee).isEqualByComparingTo("800"); // 80만 × 0.0002 × 5
    }

    @Test
    void 신청액이_한도초과면_예외() {
        assertThatThrownBy(() ->
            policy.validateWithinLimit(new BigDecimal("900000"), new BigDecimal("1000000")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```
- [ ] **Step 2:** Run `./gradlew :loan-service:test --tests "*CreditPolicyTest"` → FAIL (클래스 없음)
- [ ] **Step 3: 최소 구현**

```java
public class CreditPolicy {
    private final BigDecimal ltv;
    private final BigDecimal dailyRate;

    public CreditPolicy(BigDecimal ltv, BigDecimal dailyRate) {
        this.ltv = ltv; this.dailyRate = dailyRate;
    }
    public BigDecimal creditLimit(BigDecimal unpaidSettlementTotal) {
        return unpaidSettlementTotal.multiply(ltv);
    }
    public BigDecimal fee(BigDecimal principal, int days) {
        return principal.multiply(dailyRate).multiply(BigDecimal.valueOf(days));
    }
    public void validateWithinLimit(BigDecimal requested, BigDecimal unpaidSettlementTotal) {
        if (requested.compareTo(creditLimit(unpaidSettlementTotal)) > 0)
            throw new IllegalArgumentException("신청액이 한도를 초과합니다");
    }
}
```
- [ ] **Step 4:** Run → PASS. **Commit** `feat(loan): CreditPolicy 한도·수수료 산정`

### Task 2.3: LoanAdvance 애그리거트 — 상태머신 + 차감

**Files:**
- Create: `loan/domain/LoanAdvance.java`, `LoanStatus.java`
- Test: `.../domain/LoanAdvanceTest.java`

- [ ] **Step 1: 실패 테스트** — 다음을 검증:
  - `approve()` REQUESTED→APPROVED, 그 외 상태에서 호출 시 `IllegalStateException`.
  - `disburse()` APPROVED→DISBURSED, `outstanding == principal + fee`.
  - `applyRepayment(amount)` — `deducted = min(outstanding, amount)`, 잔액 감소, 0 도달 시 REPAID.
  - 부분상환 후 재상환 누적.
  - DISBURSED 아닌 상태에서 상환 시 예외.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: 구현** — `LoanAdvance` 에 상태 전이 가드와 `applyRepayment` 가 `min(outstanding, amount)` 반환(실제 차감액). 순수 POJO, 프레임워크 의존 0.
- [ ] **Step 4:** Run → PASS. **Commit** `feat(loan): LoanAdvance 상태머신·상환차감 도메인`

> **Chunk 2 검토 게이트.**

---

## Chunk 3: 로컬 정산 뷰 적재 (SettlementCreated 수신)

**목표:** settlement 의 정산 생성 이벤트를 받아 `seller_settlement_view` 에 멱등 적재.

### Task 3.1: SellerSettlementView 영속화

**Files:** `domain/SellerSettlementView.java`, `adapter/out/persistence/SellerSettlementViewJpaEntity.java` + `Repository` + `PersistenceAdapter`, port `LoadSettlementViewPort`/`SaveSettlementViewPort`, `V3__seller_settlement_view.sql`.

- [ ] **Step 1: 실패 테스트(@DataJpaTest 또는 Testcontainers)** — settlementId UPSERT, 셀러별 `PENDING` 합계 조회(`sumUnpaidBySeller`).
- [ ] **Step 2~4:** 마이그레이션(V3) → 엔티티/리포지토리/어댑터 구현 → 테스트 PASS. **Commit**.

### Task 3.2: SettlementCreatedConsumer (멱등)

**Files:** `adapter/in/kafka/SettlementCreatedConsumer.java`, `IngestSettlementService` (`IngestSettlementUseCase`).

- [ ] **Step 1: 실패 테스트** — 동일 event_id 두 번 수신 시 view 1건만(멱등), payload(sellerId,settlementId,amount,dueDate) 매핑.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: 구현** — `PaymentEventKafkaConsumer` 골격 복사: event_id 헤더 추출 → `ProcessedEventRepository.existsById` 멱등 체크 → `IngestSettlementService.ingest(...)` UPSERT → processed_events save → ack. `CONSUMER_GROUP="lemuel-loan"`, 토픽 `${app.kafka.topic.settlement-created}`.
- [ ] **Step 4:** Run → PASS. **Commit** `feat(loan): SettlementCreated 멱등 컨슈머 + 로컬 정산뷰`

> **Chunk 3 검토 게이트.**

---

## Chunk 4: 대출 신청·승인·실행 (선지급)

**목표:** 셀러가 대출 신청 → 한도검증 → 승인/실행 → `LoanDisbursementRequested` 발행.

### Task 4.1: Loan 영속화 (LoanAdvance ↔ JPA)
- [ ] `loan_advances`,`loan_repayments` 마이그레이션(V1) + 엔티티/리포지토리/어댑터(`LoadLoanPort`/`SaveLoanPort`). `loan_repayments(settlement_id)` **UNIQUE**(정산건당 차감 1회). @DataJpaTest 라운드트립 테스트 → **Commit**.

### Task 4.2: LoanEventPublisherAdapter (Outbox 위임)
- [ ] `PublishLoanEventPort` → `shared-common` `SaveOutboxEventPort` 로 위임하는 어댑터. `OutboxEvent`(aggregateType,eventType,payload,event_id) 생성. 단위 테스트(저장 호출 검증) → **Commit**.

### Task 4.3: RequestLoanService + DisburseLoanService

**Files:** `RequestLoanService`, `DisburseLoanService`, ports.

- [ ] **Step 1: 실패 테스트(Mockito)** —
  - `request`: `LoadSettlementViewPort.sumUnpaidBySeller` mock → `CreditPolicy.validateWithinLimit` 통과 시 LoanAdvance REQUESTED 저장.
  - 한도 초과 시 예외, 저장 호출 안 됨.
  - `disburse`: ★ **실행 직전 재검증** — 비관적 락으로 셀러 미상환잔액 재조회 후 담보 충분성 확인 → DISBURSED + `PublishLoanEventPort.publish(LoanDisbursementRequested)` 1회.
  - 재검증에서 담보 부족 시 REJECTED, 발행 안 됨.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: 구현** — `@Transactional`. 재검증 조회는 `LoadSettlementViewPort` 의 락 메서드(`...ForUpdate`) 사용.
- [ ] **Step 4:** Run → PASS. **Commit** `feat(loan): 대출 신청·실행 + 선지급 이벤트 발행`

### Task 4.4: LoanController (REST)
- [ ] `POST /loans` (신청), `POST /loans/{id}/disburse`, `GET /loans?sellerId=` 조회. `@WebMvcTest` 슬라이스 테스트(검증·상태코드) → **Commit**.

> **Chunk 4 검토 게이트.**

---

## Chunk 5: 상환 saga — loan 측 (SettlementConfirmed 수신)

**목표:** 정산 확정 이벤트 수신 → FIFO 상환 차감 → `LoanRepaymentApplied` 발행.

### Task 5.1: ApplyRepaymentService

**Files:** `ApplyRepaymentService` (`ApplyRepaymentUseCase`).

- [ ] **Step 1: 실패 테스트(Mockito)** —
  - 셀러 미상환 대출 다건 → **FIFO(가장 오래된 것부터)** 차감, 총 `deducted = min(총미상환, 정산금)`.
  - 대출 없는 셀러 → `deducted=0`.
  - 정산금 > 총미상환 → 전액 상환, 남은 정산금은 차감 안 함.
  - 차감 결과로 `LoanRepaymentApplied{settlementId, deducted}` 발행, `loan_repayments(settlementId)` 기록(멱등 1회).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: 구현** — `@Transactional`. 셀러 대출 비관적 락 조회 → 정렬(생성순) → 순차 `applyRepayment`. AppendLedgerPort 로 상환 전표 기록. PublishLoanEventPort 로 발행. settlementId UNIQUE 위반 시 멱등 무시.
- [ ] **Step 4:** Run → PASS. **Commit** `feat(loan): FIFO 상환 차감 + LoanRepaymentApplied 발행`

### Task 5.2: SettlementConfirmedConsumer
- [ ] `SettlementCreatedConsumer` 패턴 복사 → `ApplyRepaymentService.apply(settlementId, sellerId, amount)` 호출. 멱등(settlementId 기준 + processed_events). 토픽 `${app.kafka.topic.settlement-confirmed}`. 멱등 테스트 → **Commit**.

> **Chunk 5 검토 게이트.**

---

## Chunk 6: loan 자체 복식부기 원장

**목표:** 선지급/수수료/상환/대손을 자체 원장에 전표로 기록.

### Task 6.1: LoanLedgerEntry + AppendLedgerPort
- [ ] `V2__loan_ledger.sql`, `LoanLedgerEntry` 도메인(차변/대변/계정/금액/참조), `AppendLedgerPort` + 어댑터. settlement `ledger` 도메인을 참조 패턴으로(코드 의존 X) 미러링.
- [ ] **TDD**: 선지급 시 (대출채권/현금), 수수료 (미수수익/수수료수익), 상환 (현금/대출채권) 전표가 균형(차변합=대변합)인지 도메인 테스트. DISBURSE/REPAYMENT 서비스에 원장 기록 연결.
- [ ] **Commit** `feat(loan): 자체 복식부기 원장`

> **Chunk 6 검토 게이트.**

---

## Chunk 7: settlement-service 변경 (saga 상대편)

**목표:** settlement 가 이벤트를 Kafka로 발행하고, loan 응답으로 net payout 하며, 미응답을 HOLD 처리.

### Task 7.1: SettlementCreated / SettlementConfirmed Outbox 발행
**Files (Modify):** `CreateSettlementFromPaymentService.java`(생성 시 SettlementCreated), `ConfirmDailySettlementsService.java`(확정 시 SettlementConfirmed). 기존 인프로세스 ES 이벤트 경로는 **유지**, Outbox 발행을 **추가**.
- [ ] **TDD**: 정산 생성/확정 시 `SaveOutboxEventPort` 로 해당 이벤트 1건 저장되는지(Mockito) 검증. payload 스키마 확정(sellerId,settlementId,amount,dueDate). **Commit**.

### Task 7.2: payout_status 컬럼 + LoanRepaymentAppliedConsumer + net payout
**Files:** order-service `db/migration/V{ts}__settlement_payout_status.sql`(settlements 에 `payout_status` 추가), settlement `LoanRepaymentAppliedConsumer.java`(Create), payout 서비스 net 지급(Modify).
- [ ] **TDD**: `SettlementConfirmed` 발행 시 정산건 `payout_status=AWAITING_LOAN`. `LoanRepaymentApplied{deducted}` 수신 → 순지급액 `amount - deducted` 로 payout 실행, `payout_status=PAID`. 멱등(중복 수신 1회 지급). **Commit**.

### Task 7.3: PayoutHoldScheduler (loan 미응답 보상)
**Files:** `settlement/application/service/PayoutHoldScheduler.java` (Create).
- [ ] **TDD**: `AWAITING_LOAN` 상태가 타임아웃(설정값) 초과한 정산건을 스캔 → `HELD` 전환 + 경고 로그/알람. `LoanRepaymentApplied` 수신 시 해소. **차감 0 으로 자동 지급하지 않음**(보수적). **Commit**.

> **Chunk 7 검토 게이트.**

---

## Chunk 8: 종단 통합 테스트 (Testcontainers)

**목표:** PostgreSQL(×2 논리 DB 또는 스키마) + Kafka 로 전체 saga 금액 검증.

**Files:** `loan-service/src/test/.../LoanSettlementSagaIntegrationTest.java` — `@SpringBootTest` + Testcontainers. settlement-integration-test 스킬 패턴 사용.

- [ ] **시나리오 1**: SettlementCreated(예정 100만) → 선지급 80만(LTV80%) → SettlementConfirmed(100만) → loan 차감 80만+수수료 → `LoanRepaymentApplied(deducted=80만+fee)` → settlement net payout = 100만−(80만+fee). 금액 검증.
- [ ] **시나리오 2**: 대출 없는 셀러 → `deducted=0` → 전액 payout.
- [ ] **시나리오 3**: loan 미응답(컨슈머 비활성) → payout `HELD`.
- [ ] **시나리오 4**: 중복 SettlementConfirmed → 차감 1회만(멱등), `loan_repayments(settlementId)` UNIQUE.
- [ ] **시나리오 5**: 다건 대출 → FIFO 상환 순서 검증.
- [ ] **Commit** `test(loan): 선정산 대출 종단 saga 통합테스트`

> **최종 검토 게이트** → 실행 핸드오프.

---

## 미해결/실행 중 결정 필요 (구현자 주의)

1. **자체 DB 물리 구성**: 별도 PG 인스턴스 vs 동일 인스턴스 별도 DB(`lemuel_loan`). 로컬은 docker-compose 에 `lemuel_loan` DB 추가로 시작. (spec §1)
2. **Money VO 중복**: 기존 공용 금액 타입 있으면 재사용(Task 2.1 분기).
3. **일할이율/LTV 설정화**: `application.yml` 의 `app.loan.*` 로 외부화, `CreditPolicy` 빈 주입.
4. **Kafka 토픽명**: `lemuel.settlement.created`, `lemuel.settlement.confirmed`, `lemuel.loan.disbursement.requested`, `lemuel.loan.repayment.applied` — `app.kafka.topic.*` 설정으로 양 서비스 일치시킬 것.
5. **payout 도메인 net 지급 위치**: 기존 payout 서비스 구조 확인 후 `amount - deducted` 적용 지점 결정.
