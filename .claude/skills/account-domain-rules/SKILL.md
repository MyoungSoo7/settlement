---
name: account-domain-rules
description: 계정계(GL) 도메인 핵심 규칙 — 6계정·차1대1 구성적 균형, 6개 이벤트→분개 매핑, 멱등 2단, 소비 전용(발행 없음), 시산표. account-service 로직을 작성·수정·리뷰할 때 로드.
---

# 계정계 도메인 규칙 (account-service)

전사 복식부기 GL. loan·investment·settlement 이 발행하는 **6개 토픽을 소비**해 계정 간 분개로 집계한다.
**발행은 하지 않는다**(소비 전용) — shared-common 제한 스캔으로 Outbox 발행 머시너리를 배제. ledger-invariants 참조.

## 계정과목 (`domain/GlAccount.java` — 각 계정에 정상잔액 방향)

| 계정 | 방향 | 성격 |
|---|---|---|
| `CASH` | DEBIT | 현금/funding — 선지급·투자집행 유출, 상환 유입 |
| `LOAN_RECEIVABLE` | DEBIT | 셀러 선정산 대출채권 |
| `CORPORATE_LOAN_RECEIVABLE` | DEBIT | 법인(상장사) 대출채권 |
| `INVESTMENT_ASSET` | DEBIT | 투자자산 |
| `SELLER_PAYABLE` | CREDIT | 셀러 미지급금(정산 지급의무) |
| `SETTLEMENT_SCHEDULED` | DEBIT | 정산 예정 클리어링 — 생성 시 차변, 확정 시 대변 상계 |

## 전표 = 차변1·대변1·금액1 (구성적 균형, `domain/AccountEntry.java`)

- 한 전표 안에서 차변금액 = 대변금액(=`amount`) 이므로 **차대 균형이 구성적으로 보장**된다.
- 생성 불변식: `amount ≤ 0` → 예외, `debitAccount == creditAccount` → 예외.
- 자연키 `(sourceTopic, refType, refId)` — 어느 이벤트 파생인지 추적 + 스키마 UNIQUE 멱등 키.
- **단일 row(한쪽 계정만) 삽입 API 를 만들지 마라** — 반드시 정적 팩토리 6종으로 균형 전표를 생성.

## 6개 이벤트 → 분개 매핑 (계정계의 핵심 도메인 규칙)

| 소비 토픽 | 분개 | owner |
|---|---|---|
| `lemuel.settlement.created` | DR `SETTLEMENT_SCHEDULED` / CR `SELLER_PAYABLE` | SELLER |
| `lemuel.settlement.confirmed` | DR `SELLER_PAYABLE` / CR `SETTLEMENT_SCHEDULED` (예정 상계) | SELLER |
| `lemuel.loan.disbursement_requested` | DR `LOAN_RECEIVABLE` / CR `CASH` | SELLER |
| `lemuel.loan.repayment_applied` | DR `CASH` / CR `LOAN_RECEIVABLE` | SELLER |
| `lemuel.loan.corporate_loan_disbursed` | DR `CORPORATE_LOAN_RECEIVABLE` / CR `CASH` (**원금만**) | CORPORATE(stockCode) |
| `lemuel.investment.executed` | DR `INVESTMENT_ASSET` / CR `CASH` | SELLER |

- 매핑은 `AccountEntry` 정적 팩토리(`settlementCreated`/`settlementConfirmed`/`loanDisbursed`/`loanRepaid`/
  `corporateLoanDisbursed`/`investmentExecuted`)에만 존재 — 컨슈머에서 계정을 인라인 조립하지 마라.
- `loan.repayment_applied`: **`deducted ≤ 0` 이면 분개 생략**(팩토리는 양수만 허용, 컨슈머에서 스킵).
- 기업대출은 **원금만** 분개(수수료 인식은 loan 자체 원장 소관 — 계정계로 넘기지 마라).

## 멱등 2단 (idempotency-and-events 참조)

| 단 | 방어 |
|---|---|
| 1 | 컨슈머 `processed_events (consumer_group, event_id)` — group `lemuel-account`, `IdempotentEventConsumer` 상속 |
| 2 | `account_entries (source_topic, ref_type, ref_id) UNIQUE` — 재수신 시 스키마 멱등 |

- 컨슈머 골격: `@ConditionalOnProperty(app.kafka.enabled)` + `extends IdempotentEventConsumer`,
  `handle(node, eventId)` 에서 팩토리 매핑 → `RecordAccountEntryUseCase.record(entry)`, `@Transactional`.

## 시산표 (`domain/TrialBalance.java`)

- 전표 목록을 계정별 차변합/대변합으로 집계, `balanced() = totalDebit.compareTo(totalCredit)==0`.
- 각 전표가 구성적 균형이라 총차변합 == 총대변합이 **항상** 참 — `balanced()` 는 방어적 재검증값.
  `false` 가 나오면 데이터 손상 신호 (반쪽 전표가 어딘가 삽입됨) — 단일 row 삽입 경로부터 의심하라.
- 계정 enum 정의 순서로 안정 출력, 등장한 계정만 노출.

## 안티패턴 (발견 시 지적)

- 단일 계정(한쪽) row 삽입 / `amount` 음수·0 전표 / 차변=대변 동일계정.
- 이벤트→분개 매핑을 컨슈머에 인라인 (팩토리 우회 → 매핑 드리프트).
- account-service 에 이벤트 **발행** 코드 추가 (소비 전용 원칙 위반, Outbox 스캔 배제됨).
- `deducted=0` 상환에 강제로 0원 전표 생성.
- 기업대출 수수료를 계정계 분개에 포함.
