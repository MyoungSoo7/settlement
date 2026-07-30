---
name: account-domain-rules
description: 계정계(GL) 도메인 핵심 규칙 — 계정·차1대1 구성적 균형, 이벤트→분개 매핑, 멱등 2단, 소비 전용(발행 없음), 시산표. account-service 로직을 작성·수정·리뷰할 때 로드.
---

# 계정계 도메인 규칙 (account-service)

전사 복식부기 GL. loan·investment·settlement 이 발행하는 토픽을 소비해 계정 간 분개로 집계한다.
**발행은 하지 않는다**(소비 전용) — shared-common 제한 스캔으로 Outbox 발행 머시너리를 배제. ledger-invariants 참조.

## 계정과목 (`domain/GlAccount.java` — 각 계정에 정상잔액 방향)

| 계정 | 방향 | 성격 |
|---|---|---|
| `CASH` | DEBIT | 현금/funding — 선지급·투자집행 유출, 상환 유입 |
| `LOAN_RECEIVABLE` | DEBIT | 셀러 선정산 대출채권 |
| `CORPORATE_LOAN_RECEIVABLE` | DEBIT | 법인(상장사) 대출채권 |
| `SECURED_LOAN_RECEIVABLE` | DEBIT | 담보/개인신용 대출채권(SecuredLoan 계약 원금, owner=BORROWER) |
| `INVESTMENT_ASSET` | DEBIT | 투자자산 |
| `SELLER_PAYABLE` | CREDIT | 셀러 미지급금(즉시지급 의무, ADR 0026 Option ①) |
| `HOLDBACK_PAYABLE` | CREDIT | 셀러 유보 미지급금(홀드백, ADR 0026 Option ①) |
| `SELLER_RECOVERY_RECEIVABLE` | DEBIT | 지급후 회수채권(P0-6 GL mirror) |
| `WITHHOLDING_PAYABLE` | CREDIT | 원천징수 예수금(ADR 0029 §B) |
| `SETTLEMENT_SCHEDULED` | DEBIT | 정산 예정 클리어링 — cut-over 이전 역사적 계정(백필 청산 대상) |

## 전표 = 차변1·대변1·금액1 (구성적 균형, `domain/AccountEntry.java`)

- 한 전표 안에서 차변금액 = 대변금액(=`amount`) 이므로 **차대 균형이 구성적으로 보장**된다.
- 생성 불변식: `amount ≤ 0` → 예외, `debitAccount == creditAccount` → 예외.
- 자연키 `(sourceTopic, refType, refId)` — 어느 이벤트 파생인지 추적 + 스키마 UNIQUE 멱등 키.
- **단일 row(한쪽 계정만) 삽입 API 를 만들지 마라** — 반드시 정적 팩토리로 균형 전표를 생성.

## 이벤트 → 분개 매핑 (계정계의 핵심 도메인 규칙 — 대표 매핑, 전체 정본은 `AccountEntry` javadoc 표)

| 소비 토픽 | 분개 | owner |
|---|---|---|
| `lemuel.settlement.created` | DR `CASH` / CR `SELLER_PAYABLE`(즉시분) + CR `HOLDBACK_PAYABLE`(유보분 2전표) | SELLER |
| `lemuel.settlement.confirmed` | GL 무전표(상태 전이만, Option ① 이후) | SELLER |
| `lemuel.payout.completed` | DR `SELLER_PAYABLE` / CR `CASH` (초과분은 `PAYOUT_ADVANCE` 분할) | SELLER |
| `lemuel.loan.disbursement_requested` | DR `LOAN_RECEIVABLE` / CR `CASH` | SELLER |
| `lemuel.loan.repayment_applied` | DR `CASH` / CR `LOAN_RECEIVABLE` | SELLER |
| `lemuel.loan.corporate_loan_disbursed` | DR `CORPORATE_LOAN_RECEIVABLE` / CR `CASH` (**원금만**) | CORPORATE(stockCode) |
| `lemuel.loan.secured_loan_disbursed` | DR `SECURED_LOAN_RECEIVABLE` / CR `CASH` (**원금만**) | BORROWER(borrowerUserId) |
| `lemuel.loan.secured_loan_repaid` | DR `CASH` / CR `SECURED_LOAN_RECEIVABLE` (완제 — 계약 원금) | BORROWER(borrowerUserId) |
| `lemuel.investment.executed` | DR `INVESTMENT_ASSET` / CR `CASH` | SELLER |

- 이 밖에 Option ① 감액 사건 GL mirror(홀드백 해제/소진·조정·취소·회수·원천징수) 매핑이 있다 —
  값 집합·계정 조합의 정본은 `AccountEntry` 정적 팩토리(20종)와 `SchemaEnumContractIT`.
- 매핑은 `AccountEntry` 정적 팩토리에만 존재 — 컨슈머에서 계정을 인라인 조립하지 마라.
- `loan.repayment_applied`: **`deducted ≤ 0` 이면 분개 생략**(팩토리는 양수만 허용, 컨슈머에서 스킵).
- 대출류(기업·담보/개인신용)는 **원금만** 분개(이자·수수료 인식은 loan 자체 원장 소관 — 계정계로 넘기지 마라).
  담보대출 완제(`secured_loan_repaid`)의 `principal` 은 계약 원금이라 실행 전표와 동액 — 채권이 0 으로 닫힌다.

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
