---
name: loan-domain-rules
description: 대출 도메인 핵심 규칙 — 여신 4종(선정산·기업신용·담보/개인신용·물건금융) 상태머신, 신용/담보 정책 구간·한도·수수료, 담보 감시(마진콜·청산), 리스 스케줄, 원장 전표, 상환 saga. loan-service 로직을 작성·수정·리뷰할 때 로드.
---

# 대출 도메인 규칙 (loan-service)

**여신 4종**이 한 서비스에 공존한다.

| 상품 | 담보/재원 | 심사 근거 | 상환 |
| --- | --- | --- | --- |
| 선정산 대출(`LoanAdvance`) | 미지급 정산예정금 | 정산합계 × LTV × 평판 haircut | 정산 확정 saga(FIFO 자동차감) |
| 기업 신용대출(`CorporateLoan`) | 무담보 | 신용점수 100점(안정성·수익성·평판) | 명시적 `repay(amount)` |
| 담보/개인신용(`SecuredLoan`) | 부동산·금융자산·보증 / 무담보 | 담보가치 × 유형별 LTV / CB 등급 | 상환·중도상환(수수료) |
| 물건금융(`LeaseContract`) | 물건 자체 | 취득원가·선수금·보증금·잔가 기반 스케줄 | 월 납입 |

정책은 모두 결정적 순수 함수(구간 매핑)라 경계값 전수 단위테스트로 회귀를 차단한다.
**정책 클래스 밖에서 점수·한도·수수료를 인라인 계산하면 경계값 테스트를 우회한다 — 금지.**

## 상태머신 (setter 로 status 직접 변경 금지)

```
LoanAdvance(선정산):   REQUESTED → APPROVED → DISBURSED → REPAID
                                 ↘ REJECTED    ↘ OVERDUE → WRITTEN_OFF
CorporateLoan(기업신용): REQUESTED → APPROVED → DISBURSED → REPAID
                                   ↘ REJECTED
SecuredLoan(담보/개인):  REQUESTED → APPROVED → DISBURSED → REPAID
                                   ↘ REJECTED  ↘ OVERDUE → DEFAULTED → WRITTEN_OFF
Lease(물건금융):        APPLIED → APPROVED → ACTIVE → MATURED
                              ↘ REJECTED       ↘ OVERDUE → DEFAULTED
                              ↘ CANCELLED      ↘ EARLY_TERMINATED
Collateral: PLEDGED → ACTIVE → RELEASED        MarginCall: OPEN → RESOLVED | ESCALATED
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

## 담보/개인신용 정책 (`domain/SecuredLoanPolicy.java`)

상품 3종: `MORTGAGE`(주택담보) · `FINANCIAL_ASSET`(금융자산담보) · `PERSONAL_CREDIT`(개인신용).

**한도**
- 담보형: `유효담보가치 × 담보인정비율`. 유형별 비율 — **GUARANTEE 1.00 / DEPOSIT 0.95 / BOND 0.80 /
  EQUITY 0.60**, 부동산(REAL_ESTATE)만 **주입값**(LTV 규제가 바뀌는 축이라 운영 중 조정 대상).
- 개인신용: 등급별 **정액** — A 1억 / B 5천만 / C 3천만 / D 1천만. E·미상은 0.

**등급·금리**
- CB 점수 → 등급: **≥850 A, ≥750 B, ≥650 C, ≥550 D, 그 외 E**(이상 매칭·내림차순 밴드).
- **미상(null)도 대출 차단**한다. 선정산의 평판 haircut 이 fail-open 인 것과 **반대**인 이유: 선정산은
  담보(정산예정금)가 이미 있고 평판은 가중치일 뿐이지만, 여기서 CB 등급은 무담보 신용대출의 **유일한**
  심사 근거라 부재를 통과시킬 수 없다. 이 비대칭을 "일관성"을 이유로 맞추려 하지 말 것.
- 적용 연이율 = 기준금리 + 가산금리. 담보형은 **고정 0.8%p**(담보가 위험을 흡수하므로 등급 무관),
  신용형은 등급별 **A 1.5 / B 2.5 / C 4.0 / D 6.0 %p**.

**보증서**: 보증료율 연 **1.2%**, 보증비율 **85%** — 나머지 15%는 우리 신용리스크다. 대위변제 회수액은
보증비율만큼이며 미보증분은 상각된다(보증부라도 손실이 0이 아닌 이유).

**담보 감시 — 담보유지비율 = 유효담보가치 / 미상환잔액**
- **< 1.40 → 마진콜**(추가담보 요구액 = 부족분), **< 1.20 → 강제처분 이관**.
- 재평가와 판정은 **한 유스케이스**다(`RevalueCollateralUseCase`) — 값만 기록하고 판정을 미루면 그 사이
  담보 부족이 방치된다. 시가를 새로 안 시점이 곧 조치를 결정해야 하는 시점이다.
- 실행(`EnforceCollateralUseCase`)은 담보 계열로 갈린다: 부동산·금융자산은 **처분**(매각대금 회수 후
  부족분 상각), 보증부는 처분 대상이 없으므로 **대위변제 청구**.
- 진입점은 `CollateralController`(`/loans/secured/{loanId}/collateral/{revalue,dispose,subrogate}`, 운영자 전용,
  처분·대위변제는 `Idempotency-Key` 선점). **2026-08-13 이전에는 이 진입점이 없어 판정이 런타임에서
  도달 불가였다** — 서비스·정책·단위테스트가 다 있어도 부르는 어댑터가 없으면 기능은 존재하지 않는다.
  재발은 `InboundPortReachabilityTest` 가 구조로 막는다.

**중도상환수수료**: 요율 **1.2%**, 부과기간 **1095일(3년)**. 부과기간을 분모로 한 **taper** 라 잔여기간이
0이 되는 순간 수수료도 0이 된다 — 면제가 계단이 아니라 taper 의 자연스러운 끝점이다.

## 물건금융(리스·할부) 정책 (`domain/LeaseSchedule.java`·`LeaseContract.java`)

상품 3종: `FINANCE_LEASE` · `OPERATING_LEASE` · `INSTALLMENT`.

**스케줄 산식**
```
financed(리스원금) = 취득원가 − 선수금 − 보증금
월이율 i = 연이율% / 100 / 12
i = 0    → 월납입 = (financed − 잔존가치) / n
i > 0    → 월납입 = (financed − 잔존가치/(1+i)^n) × i(1+i)^n / ((1+i)^n − 1)   (연금현가식)
```
- 불변식: `financed > 0`, **`잔존가치 < financed`**(회수할 원금이 남아야 한다), `termMonths ≥ 1`, 연이율 ≥ 0.
- 반올림은 `RoundingPolicy` 주입 — 도메인이 스스로 정하지 않는다.
- **중도해지**(`EarlyTerminationQuote`): 잔여원금 + 위약금, 위약금률 상한 **10%**.

**개시(activate)**: `LEASE_RECEIVABLE` 전표 + `lemuel.loan.lease_activated` 발행. 상태는 `ACTIVE`.
승인 전에는 취소(`CANCELLED`)·반려(`REJECTED`)만 가능하다.

## 금액·원장 (money-safety, ledger-invariants 참조)

- 전 계산 **BigDecimal**, 라운딩 `HALF_UP` 명시. `new BigDecimal("0.8")` 문자열 생성 패턴 준수.
- loan 자체 복식부기 원장 `LedgerAccount` **10종**: LOAN_RECEIVABLE·CASH·FEE_RECEIVABLE·FEE_INCOME·
  BAD_DEBT_EXPENSE·BAD_DEBT_ALLOWANCE·GUARANTEE_FEE_EXPENSE·COLLATERAL_DISPOSAL_LOSS·
  COLLATERAL_DISPOSAL_GAIN·LEASE_RECEIVABLE(리스 개시).
- **기업대출 실행 = 전표 2건**(`DisburseCorporateLoanService`): 선지급(`corporateDisbursement` 대출채권/현금)
  \+ 수수료 인식(`corporateFeeAccrual` 미수수익/수수료수익, **fee>0 일 때만**). 도메인 저장·전표·이벤트가 **한 트랜잭션**.
- 이중지급 방어: `findByIdForUpdate` 비관적 락으로 disburse — 동시 요청 시 전표·이벤트 중복 차단.

## 이벤트 (idempotency-and-events 참조)

- 발행(전부 Outbox 경유, aggregateType=`Loan`) — 계약 스키마 **7종**이 정본
  (`shared-common/src/testFixtures/resources/contracts/events/lemuel.loan.*.schema.json`, ADR 0024):
  `disbursement_requested` · `corporate_loan_disbursed` · `repayment_applied` · `secured_loan_disbursed` ·
  `secured_loan_repaid` · `secured_loan_principal_repaid` · `lease_activated`.
  직접 `kafkaTemplate.send()` 금지 — `SaveOutboxEventPort.save(OutboxEvent.pending(...))`.
- 수신: `settlement.created`(SettlementCreatedConsumer), `settlement.confirmed`(SettlementConfirmedConsumer),
  `company.reputation_changed`(평판 프로젝션).

## 상환 saga (`ApplyRepaymentService` — 선정산 대출)

- 정산 확정 시 셀러 미상환 대출을 **FIFO(오래된 순)** 로 락 조회 후 순차 차감 → 차감총액을
  `LoanRepaymentApplied` 발행 → settlement 가 순지급액(`amount - deducted`)으로 payout.
  **지급 트리거(L-3, 2026-08-13 배선)**: settlement 는 이 이벤트를 받아야 지급을 만든다 — 확정 배치는
  지급을 만들지 않는다. 즉 `repayment_applied` 발행이 셀러 지급의 **유일한 트리거**이고,
  차감 순서(원천징수 → 대출차감 → 채권상계) 정본은 📘`settlement-domain-rules` 의
  "지급액 차감 순서" 절이다.
- **멱등 3중**: `recordRepaymentPort.existsForSettlement(settlementId)` 선체크 + 컨슈머 `processed_events`
  \+ `loan_repayments.settlement_id UNIQUE`(스키마 최종 방어).
- **차감 0(대출 없음)이어도 record·publish 한다** — settlement 가 전액 지급하도록 통지해야 멱등·정합이 성립.
  이 발행을 생략하는 "최적화"는 반려하라. 전표는 차감>0 일 때만.

## 안티패턴 (발견 시 지적)

- status setter 직접 변경 / 상태 전이 메서드 우회.
- disburse 에 비관적 락 없이 이중지급 노출.
- 상환 차감 0일 때 `LoanRepaymentApplied` 발행 생략 (settlement 지급 멈춤 유발).
- 신용점수/한도/수수료를 정책 클래스 밖에서 인라인 계산 (경계값 테스트 우회).
- **[담보]** CB 등급 미상(null)을 통과시킴 — 선정산의 평판 fail-open 과 혼동한 결과. 무담보 신용의
  유일한 심사 근거라 부재는 차단이 정답이다.
- **[담보]** 재평가만 기록하고 마진콜·청산 판정을 뒤로 미룸 (그 사이 담보 부족이 방치된다).
- **[담보]** 담보 유형별 LTV·등급별 정액한도·가산금리를 상수 표 밖에서 분기 (표가 정본).
- **[리스]** 잔존가치 ≥ financed 를 허용 — 회수할 리스 원금이 남지 않는다(불변식 위반).
- **[리스]** 반올림을 도메인이 자체 결정 — `RoundingPolicy` 주입이 정본.
- **[공통]** 유스케이스를 만들고 인바운드 어댑터 배선을 빠뜨림 — 단위 테스트는 초록불인 채 기능이
  런타임에 존재하지 않는다. `InboundPortReachabilityTest` 가 잡지만, 애초에 배선까지가 한 작업이다.
