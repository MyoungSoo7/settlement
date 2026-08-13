---
name: card-service-rules
description: 법인카드 도메인 핵심 규칙 — 재원 F 공식과 account-service 조회, master_limit >= Σ sub_limit 불변식과 비관적 락, 하향 클램프, 재원 조회 폴백 금지, sumActiveSubLimits 가 SUSPENDED 를 포함하는 이유, Phase 2(승인/매입/명세서/지출관리) 도메인 규칙. card-service 로직을 작성·수정·리뷰할 때 로드.
---

# 법인카드 도메인 규칙 (card-service)

셀러 조직에 **마스터 한도**를 주고, 그 안에서 임직원별 **서브한도** 카드를 발급한다.
**1단계**(발급·한도·상태·프로젝션)는 완료. **2단계**(실시간 승인·매입·명세서·지출관리)도 완료(2026-08-04).

## Phase 2 도메인 규칙 — 승인(Authorization)

### 가용한도 불변식

```
가용한도 = masterLimit − sumActiveHoldsByAccount − sumCapturesByAccount
```

- `AuthorizeCardService` 는 **반드시** `findByIdForUpdate` 비관적 락 후 한도를 검증한다.
- 동시 승인 경합은 `ConcurrentAuthorizationIT` 로 실증됨.
- `authorizationId` 는 VAN 멱등키 — `findByAuthorizationId` 로 선조회, 존재하면 기존 홀드 반환(멱등).

### DeclineReason enum 확정값 (4개 — 추가 금지)

| 값                          | 의미                                          |
| --------------------------- | --------------------------------------------- |
| `LIMIT_EXCEEDED`            | 카드/계정 한도 초과                           |
| `CARD_SUSPENDED`            | 카드 또는 계정 정지/연체 상태                 |
| `MEMBER_INACTIVE`           | 조직에서 비활성화된 임직원                    |
| `MERCHANT_POLICY_VIOLATION` | MCC 차단·1회/일/월 한도·해외/온라인 정책 위반 |

- **DELINQUENT 계정 승인 거절 코드는 `CARD_SUSPENDED`** — 별도 `DELINQUENT` 코드 추가 금지.
- DeclineReason 을 추가하면 ADR 0022 파괴적 변경이다(스키마 enum 변경).

### HoldStatus 생명주기

```
ACTIVE → CAPTURED (전액 매입)
ACTIVE → PARTIALLY_CAPTURED (부분 매입, 잔여 홀드 계속 ACTIVE)
ACTIVE → VOIDED (취소)
ACTIVE → EXPIRED (만료 배치 — HoldExpiryScheduler)
```

- EXPIRED 배치는 ShedLock(`lockAtMostFor=PT1H`), 일 1회.

## Phase 2 도메인 규칙 — 매입(Capture)·취소·환불

- **매입(Capture)**: 홀드 소진 + `CardCapture` 생성. 부분 매입은 홀드 `PARTIALLY_CAPTURED` + 잔여 홀드 감소.
- **취소(Void)**: 홀드 `VOIDED` + masterLimit 원복(Outbox `CardVoided`).
- **환불(Refund)**: 매입 후 환불 — `CardCapture` 상태 `REFUNDED` + masterLimit 원복. `refundId` L3 멱등.
- 금액은 전부 `BigDecimal`, Outbox 이벤트에선 `toPlainString()`(DATA-STANDARD N5).

## Phase 2 도메인 규칙 — 명세서·청구(Statement/Billing)

### StatementStatus 생명주기

```
OPEN → CLOSED (마감 배치 — CloseStatementScheduler)
CLOSED → PARTIALLY_PAID (일부 납부)
CLOSED / PARTIALLY_PAID → PAID (전액 납부)
CLOSED / PARTIALLY_PAID → DELINQUENT (연체 배치 — DelinquencyBatchScheduler)
DELINQUENT → PAID (전액 납부 후 ACTIVE 복구)
```

### 상환(Payment) 멱등

- `paymentId` = L3 멱등키 (`statement_payments.payment_id UNIQUE`).
- 전액 납부(`paidAmount >= totalAmount`) → `StatementStatus.PAID` + `lemuel.card.statement_paid` Outbox 발행.
- 계정 복구: DELINQUENT 명세서의 전액 납부 시 `CardAccount.recoverFromDelinquency()` → ACTIVE.

### 연체(Delinquency) 배치

- **명세서 1건 = 트랜잭션 1건** (`DelinquentStatementProcessor`, `REQUIRES_NEW` — self-invocation 안티패턴 참조).
- DELINQUENT 계정 승인: `DeclineReason.CARD_SUSPENDED` 반환.
- 이미 DELINQUENT 인 계정에 중복 전이 시도 → 스킵(멱등).

## Phase 2 도메인 규칙 — 지출관리 SaaS

### ExpenseReportStatus 생명주기

```
DRAFT → SUBMITTED (임직원 제출)
SUBMITTED → APPROVED (관리자 승인)
SUBMITTED → REJECTED (관리자 반려)
REJECTED → SUBMITTED (재제출 가능)
```

### 비결합 원칙 (핵심)

- `AuthorizeCardService` 는 `ExpenseReport` 를 참조하지 않는다 — `AuthorizationLatencyTest` p99 ≤ 300ms + `ExpenseWorkflowDecouplingTest`(ArchUnit) 로 기계 강제.
- 경비보고서 생성은 `CardCaptured` Kafka 이벤트를 `CardCapturedExpenseConsumer` 가 소비해 사후 처리.
- `captureId` 는 L3 멱등키 (`expense_reports.capture_id UNIQUE`).

### 부서 예산

- `DepartmentBudget`: 부서별 월 예산 총액·소진액. 지출 승인 워크플로와 별도로 집계만 제공.
- 예산 초과 시 승인 거절은 **범위 밖** — 이 시스템은 지출 후 증빙/카테고리 관리 SaaS다.

## 재원 F — 왜 account-service 에 물어보는가

```
F = sellerPayable + holdbackPayable      (확정·미지급 정산금 + 홀드백 유보분)
R = 인정비율 (app.card.limit.recognition-ratio, 기본 0.70)
H = 평판 haircut (A·B 1.00 / C 0.85 / D 0.70 / E 0.00)
masterLimit = floor(F x R x H)           (원 단위 절사 — 반올림으로 1원도 더 주지 않는다)
```

- **card 는 재원을 자체 보관하지 않는다.** `sellerPayable` 은 account-service GL 통제계정의 잔액이고,
  거기가 유일한 정답지다(ADR 0030). card 가 복제해 두면 정산·조정·취소가 반영되지 않은 낡은 재원으로
  여신을 내주게 되고, 그 오차는 카드가 이미 긁힌 뒤에 드러난다.
- **R 이 1 이 아닌 이유**: F 는 곧 셀러에게 지급될 돈이라, 카드 이용과 정산 지급이 **같은 재원을 두 번**
  쓸 수 있다. 실제 상계는 3단계(청구 사이클)의 몫이고, 그때까지 R 이 그 위험을 흡수한다.
  → **스펙 §2.1 의 한계: 재원 이중 사용은 1단계에서 해소되지 않는다. 3단계 담당자는 이 R 을
  상계 구현과 함께 재조정해야 한다.** 이 문장을 지우려면 상계가 먼저 있어야 한다.
- 스냅샷은 **부호 있는 원본값**을 보존하고 클램프는 **합계 F 에만** 적용한다. 각 항을 개별로 0 바닥치면
  `sellerPayable=-500000, holdbackPayable=1000000` 에서 진짜 재원 500,000 을 1,000,000 으로 부풀린다.

## master_limit >= Σ sub_limit — 락이 유일한 수단이다

CardAccount 와 Card 는 **다른 애그리거트**다. Postgres 는 CHECK 절에서 다른 테이블을 집계할 수 없어
이 불변식을 DB 제약으로 표현할 방법이 없다. 그래서 **순서가 곧 방어**다:

1. `LoadCardAccountPort.findByIdForUpdate(id)` — 계정 행을 PESSIMISTIC_WRITE 로 잠근다
2. **그 다음** `LoadCardPort.sumActiveSubLimits(cardAccountId)` 를 재계산한다
3. 검증 → 저장 → 이벤트를 **같은 트랜잭션**에서

순서를 뒤집거나 락을 생략하면 "합계를 읽은 뒤 반영하기 전"의 창으로 동시 발급이 통과한다.
`CardIssuanceLimitConcurrencyIT` 가 전적으로 이 락에 의존한다 — 통과한다고 락을 빼도 되는 게 아니라,
**빼면 그 테스트가 깨지는 것이 정상**이다. 반드시 활성 트랜잭션 안에서 호출해야 락이 유지된다.

## 하향 클램프

`CardAccount.changeMasterLimit(newLimit, currentSubLimitSum)` 은 하향을 **Σ서브한도 하한에서 멈춘다**
(`LimitChangeResult.clamped=true`). 이미 발급된 카드를 통지 없이 죽이지 않기 위해서다.

- 클램프됐다는 사실은 DB 상태만으로 알 수 없다(800,000 이 산정값인지 하한인지 구분되지 않는다).
  그래서 `clamped` 는 `lemuel.card.limit_changed` 페이로드에 **반드시** 실린다.
- 재산정 탈락(E등급·최소한도 미달)은 **한도 0 이 아니라 계정 SUSPENDED** 로 표현한다. 한도만 0 으로
  두면 계정은 ACTIVE 인 채 카드가 사실상 무력화돼 사용자도 상담원도 원인을 모르고, 남은 클램프
  한도만큼 신규 발급까지 계속 통과한다.

## 재원 조회에 폴백을 두지 않는다

`LoadSellerFundingPort.load()` 는 실패 시 `FundingUnavailableException` 을 던지고 **끝이다**
(→ `ErrorCode.CARD_FUNDING_UNAVAILABLE` → **503**).

- 400/422 가 아닌 이유: 신청이 잘못된 게 아니라 **우리가 지금 판단할 수 없다**. 재시도하면 성공할 수 있다.
- 기본값·캐시·"직전 한도 유지" 같은 폴백은 전부 **추정 한도 부여**이고, 추정으로 여신을 내주는 것이
  곧 사고다. 가용성을 위해 폴백을 넣자는 제안은 반려하라 — 여기서의 가용성은 잘못된 여신과 같은 말이다.
- 일 1회 재산정 배치에서만은 **계정 단위로** 실패를 삼킨다(그 계정은 옛 한도 유지, 나머지는 계속).
  배치 전체를 세우면 모든 계정이 옛 한도로 남는데, 그게 이 배치가 막으려던 위험이다.

## sumActiveSubLimits 는 SUSPENDED 를 포함한다

집계 조건은 `status <> 'CANCELED'` 다 — **정지 카드도 한도를 계속 점유한다.**

- 정지가 한도를 풀어주면, 휴직·이탈로 정지된 자리에 새 카드가 발급된 뒤 **복직 시 돌아갈 한도가 없다**.
  복직자의 카드를 되살리는 순간 `master >= Σsub` 가 깨지거나, 남의 한도를 빼앗아야 한다.
- 해지(CANCELED)만이 한도를 반환한다. 되돌릴 수 없는 종결 상태이기 때문이다.
- `CANCELED → SUSPENDED` 는 금지된 전이다. 이탈 처리에서 카드를 무조건 정지시키면 해지 카드에서
  예외가 터져 **조직 프로젝션 비활성화까지 롤백**된다 — 권한 회수 자체가 사라진다.
  그래서 조회를 `findActiveByHolder`(CANCELED 제외)로 하고, 그것이 곧 가드다.

## 이벤트 (idempotency-and-events · event-contract-change 참조)

- 전부 Outbox 경유(`SaveOutboxEventPort.save(OutboxEvent.pending(...))`). 직접 `kafkaTemplate.send()` 금지.
- 토픽은 `aggregateType + eventType` 에서 파생된다. **파티션 키(aggregateId)는 언제나 cardAccountId** —
  cardId 로 잡으면 같은 계정의 발급·한도변경이 다른 파티션으로 흩어져 소비자가 순서를 잃는다.

| eventType                | 토픽                                 | 비고                                                       |
| ------------------------ | ------------------------------------ | ---------------------------------------------------------- |
| CardAccountOpened        | `lemuel.card.account_opened`         |                                                            |
| CardIssued               | `lemuel.card.issued`                 |                                                            |
| CardLimitChanged         | `lemuel.card.limit_changed`          | `scope` 가 MASTER/SUB 유일 구분자, MASTER 는 `cardId=null` |
| CardStatusChanged        | `lemuel.card.status_changed`         | 임직원 카드(개인 사건 — 이탈·분실)                         |
| CardAccountStatusChanged | `lemuel.card.account_status_changed` | 법인 계정(여신 사건 — 재산정 강등·수동 조치)               |
| CardAuthorized           | `lemuel.card.authorized`             | 승인 홀드 생성 (Phase 2)                                   |
| CardCaptured             | `lemuel.card.captured`               | 매입 확정 (Phase 2)                                        |
| CardStatementPaid        | `lemuel.card.statement_paid`         | 명세서 전액 납부 완료 (Phase 2)                            |

- 카드 상태와 계정 상태를 **한 토픽에 섞지 않는다**: 상태 enum 도 소비자도 다르고, 섞으면 cardId 가
  있는 페이로드와 없는 페이로드를 소비자가 런타임에 분기해야 한다.
- 금액은 전부 JSON string(`toPlainString`, DATA-STANDARD N5). REST 응답은 `BigDecimal`.

## 일 1회 한도 재산정 (3:30 KST)

- 3:00 이 아니라 3:30 인 이유: 정산 확정 배치(settlement-service, 3:00)가 먼저 끝나야 한다.
  겹치면 확정 도중의 재원으로 산정해 매일 조금씩 다른 답이 나온다. `zone` 을 KST 로 고정(UTC JVM 대비).
- `@SchedulerLock(name="card-limit-recalculation", lockAtMostFor=PT30M)` — 없으면 replica 수만큼 중복 실행.
  계정 수가 늘면 `lockAtMostFor` 도 함께 올려야 한다.
- **계정 1건 = 트랜잭션 1건**(`CardAccountRescreener`, `REQUIRES_NEW`). 배치 서비스에 `@Transactional`
  을 붙이거나 재심사를 private 메서드로 내리면(self-invocation → 프록시 미적용) 배치 전체가 한
  트랜잭션이 되어 **한 계정의 실패가 앞서 성공한 계정을 전부 롤백**시킨다.
- 대상은 ACTIVE 계정뿐이다. **강등된 계정은 자동 복구되지 않는다** — 재원이 회복돼도 복귀는 사람이
  `resume` 을 누르는 결정이다(정지 사유 해소 여부는 재원 숫자가 답할 수 있는 질문이 아니다).
- 무변경이면 저장·발행 모두 생략한다. 매일 전 계정에 이벤트를 내면 진짜 변경이 노이즈에 묻히고,
  version 만 튕겨 낙관적 락 충돌이 는다.

## 안티패턴 (발견 시 지적)

- `sumActiveSubLimits` 를 **락 획득 전에** 읽거나, 락 없이 한도를 변경.
- 재원 조회 실패에 기본값·캐시·직전 한도 폴백.
- `CardAccount.Builder` 로 새 인스턴스를 조립해 상태 전이 가드 우회 (빌더는 영속 계층 재구성 전용).
- 정지 카드를 `sumActiveSubLimits` 에서 제외 (복직 시 한도 충돌).
- 재산정 탈락을 한도 0 으로만 표현하고 계정 상태를 그대로 둠.
- 계정 상태 변경을 `lemuel.card.status_changed` 로 발행 (토픽 혼선).
- 이벤트 파티션 키를 cardId 로 사용.
- **[Phase 2]** DeclineReason 에 DELINQUENT·PENDING_APPROVAL 등 추가 — ADR 0022 파괴적 변경 (스키마 enum 변경 → 소비자 파싱 실패).
- **[Phase 2]** 연체 배치를 단일 서비스 빈 self-invocation 으로 구현 — REQUIRES_NEW 트랜잭션이 적용 안 됨 (별도 `DelinquentStatementProcessor` 빈 필수).
- **[Phase 2]** `AuthorizeCardService` 에서 `ExpenseReport`·`ExpenseWorkflowService` 를 직접 참조 — 승인 경로 오염 (ArchUnit + AuthorizationLatencyTest 로 기계 강제).
- **[Phase 2]** 납부 금액을 `int`/`long` 으로 다루거나 JSON 에서 number 로 직렬화 — DATA-STANDARD N5 위반 (BigDecimal·toPlainString 강제).
