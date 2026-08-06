---
name: loan-domain-rules
description: 대출 도메인 핵심 규칙 — 선정산 대출(LoanAdvance)·기업 신용대출(CorporateLoan) 상태머신, 신용정책 구간·한도·수수료, 원장 2전표, 상환 saga. loan-service 로직을 작성·수정·리뷰할 때 로드.
---

# 대출 도메인 규칙 (loan-service)

두 종류의 대출이 공존한다 — **선정산 대출**(정산예정금 담보) vs **기업 신용대출**(상장사 무담보 신용).
정책은 모두 결정적 순수 함수(구간 매핑)라 경계값 전수 단위테스트로 회귀를 차단한다.

## 상태머신 (setter 로 status 직접 변경 금지)

```
LoanAdvance(선정산):   REQUESTED → APPROVED → DISBURSED → REPAID
                                 ↘ REJECTED    ↘ OVERDUE → WRITTEN_OFF
CorporateLoan(기업신용): REQUESTED → APPROVED → DISBURSED → REPAID
                                   ↘ REJECTED
```

- `CorporateLoan` 상환은 셀러 정산 saga 가 아니라 **명시적 `repay(amount)`** 로 미상환잔액 차감 (무담보).
- `LoanAdvance` 상환은 정산 확정 이벤트 기반 saga (아래).

## 기업 신용대출 신용정책 (`application/service/CorporateCreditPolicy.java`)

신용점수(0~100) = **안정성 40 + 수익성 40 + 평판 20**:

| 축 | 지표 | 구간 |
|---|---|---|
| 안정성 40 | 부채비율(%) | ≤100→40, ≤200→30, ≤300→20, ≤400→10, 초과/자본잠식(null)→0 |
| 수익성 40 | 영업이익률(%) 0~20 | ≥20→20, ≥10→15, ≥5→10, ≥0→5, 음수/null→0 |
| 수익성 40 | ROA(%) 0~20 | ≥10→20, ≥5→15, ≥2→10, ≥0→5, 음수/null→0 |
| 평판 20 | 뉴스 평판 등급 | A20 B15 C10 D5 E0, **미상(null)=10(중립)** |

- 등급: **≥80 A, ≥65 B, ≥50 C, ≥35 D, <35 E** — `creditGrade(score)`. **E 는 대출 불가**(`isLoanBlocked`).
- 한도 = `자본총계 × equityLimitRatio(기본 0.10) × gradeFactor`, `setScale(2, HALF_UP)`.
  - gradeFactor: **A1.0 B0.8 C0.6 D0.3 E0**. 자본총계 null/≤0 → 한도 0.
- 수수료 = `원금 × dailyRate × termDays × gradeSurcharge`, `setScale(2, HALF_UP)`.
  - gradeSurcharge: **A1.0 B1.1 C1.25 D1.5**. `termDays<0` → `IllegalArgumentException`.
- 파라미터: `app.loan.daily-rate`(선정산과 공용), `app.loan.corporate.equity-limit-ratio:0.10`.
- 거절 규칙: E등급 또는 신청액>한도 → `CorporateLoanRejectedException`(→422).

## 선정산 대출 정책 (`application/service/CreditPolicy.java`)

- 한도 = `미지급 정산예정금 합계 × LTV × 평판 haircut(등급)`.
- 평판 haircut: **A·B=1.0, C=0.85, D=0.70, E=0.0(차단)**. 미상/미등록 등급 → **1.0(fail-open)** —
  평판 데이터 부재가 대출을 막지 않는다.
- 수수료 = `선지급액 × dailyRate × 선지급일수`. `days<0` → `IllegalArgumentException`.
- `validateWithinLimit(requested, unpaidTotal, grade)`: 신청액>한도 → 예외 (E등급/haircut 사유 포함).

## 금액·원장 (money-safety, ledger-invariants 참조)

- 전 계산 **BigDecimal**, 라운딩 `HALF_UP` 명시. `new BigDecimal("0.8")` 문자열 생성 패턴 준수.
- loan 자체 복식부기 원장 `LedgerAccount`: LOAN_RECEIVABLE·CASH·FEE_RECEIVABLE·FEE_INCOME·BAD_DEBT_EXPENSE·BAD_DEBT_ALLOWANCE.
- **기업대출 실행 = 전표 2건**(`DisburseCorporateLoanService`): 선지급(`corporateDisbursement` 대출채권/현금)
  \+ 수수료 인식(`corporateFeeAccrual` 미수수익/수수료수익, **fee>0 일 때만**). 도메인 저장·전표·이벤트가 **한 트랜잭션**.
- 이중지급 방어: `findByIdForUpdate` 비관적 락으로 disburse — 동시 요청 시 전표·이벤트 중복 차단.

## 이벤트 (idempotency-and-events 참조)

- 발행(Outbox 경유): `CorporateLoanDisbursed`(aggregateType=`Loan`) → `lemuel.loan.corporate_loan_disbursed`,
  `LoanRepaymentApplied` → `lemuel.loan.repayment_applied`, `disbursement_requested`.
  직접 `kafkaTemplate.send()` 금지 — `SaveOutboxEventPort.save(OutboxEvent.pending(...))`.
- 수신: `settlement.created`(SettlementCreatedConsumer), `settlement.confirmed`(SettlementConfirmedConsumer).

## 상환 saga (`ApplyRepaymentService` — 선정산 대출)

- 정산 확정 시 셀러 미상환 대출을 **FIFO(오래된 순)** 로 락 조회 후 순차 차감 → 차감총액을
  `LoanRepaymentApplied` 발행 → settlement 가 순지급액(`amount - deducted`)으로 payout.
- **멱등 3중**: `recordRepaymentPort.existsForSettlement(settlementId)` 선체크 + 컨슈머 `processed_events`
  \+ `loan_repayments.settlement_id UNIQUE`(스키마 최종 방어).
- **차감 0(대출 없음)이어도 record·publish 한다** — settlement 가 전액 지급하도록 통지해야 멱등·정합이 성립.
  이 발행을 생략하는 "최적화"는 반려하라. 전표는 차감>0 일 때만.

## 안티패턴 (발견 시 지적)

- status setter 직접 변경 / 상태 전이 메서드 우회.
- disburse 에 비관적 락 없이 이중지급 노출.
- 상환 차감 0일 때 `LoanRepaymentApplied` 발행 생략 (settlement 지급 멈춤 유발).
- 신용점수/한도/수수료를 정책 클래스 밖에서 인라인 계산 (경계값 테스트 우회).
