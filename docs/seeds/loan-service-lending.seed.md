# Seed — loan-service 여신 as-is 사양

> 상태: CONFIRMED (settlement/account seed 와 동일 방식 — 역산 결정화)
> 관련 문서: `docs/prd/loan-service.md`(PRD) · 스킬 `loan-domain-rules`(강제 규칙 정본)
> 자매 Seed: `account-service-gl-core`(대출 이벤트를 GL 분개로 소비하는 하류)

## Goal (한 줄)

**loan-service(여신 — 선정산 대출·기업 신용대출·담보대출·물건금융을 한 서비스에서 다루는 컨텍스트)의
현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 계약 드리프트 게이트 ·
면접/포트폴리오 문서로 쓴다.**

## 범위

**포함**

- 상태머신 2종(선정산 `LoanStatus`·기업신용 `CorporateLoanStatus`)과 전이 강제
- 기업 신용정책(점수→등급→한도/수수료) 구간 규칙
- 자체 복식부기 원장(차1·대1 균형)과 전표 팩토리
- 선정산 상환 충당 규칙
- 물건금융(리스·할부)의 잔가 허용/필수 구분
- 발행·소비 이벤트 표면

**제외**

- 담보 재평가·마진콜 상세(`Collateral*`·`MarginCall*` — 별도 Seed 대상)
- 외부 재무제표·평판 API 클라이언트 페이로드

## 핵심 불변식 (as-is, 파일:라인 근거)

경로 접두 `loan-service/src/main/java/github/lms/lemuel/loan/`

| # | 불변식 | 근거 |
|---|---|---|
| 1 | **선정산 상태머신 7상태** — REQUESTED·APPROVED·DISBURSED·REPAID·REJECTED·OVERDUE·WRITTEN_OFF, 전이 단일 출처 | `domain/LoanStatus.java:13-19,22,35` |
| 2 | **기업신용 상태머신 5상태** — REQUESTED·APPROVED·DISBURSED·REPAID·REJECTED | `domain/CorporateLoanStatus.java:16-20,23,31` |
| 3 | **전이 규칙은 enum 이 단일 출처** — 애그리거트는 그 판정을 호출할 뿐(settlement 의 `SettlementStatus` 와 동형) | `LoanStatus.java:22` · `CorporateLoanStatus.java:23` |
| 4 | **신용등급 5구간** — ≥80 A, ≥65 B, ≥50 C, ≥35 D, <35 E. **E 는 대출 불가** | `domain/CorporateCreditPolicy.java:26` |
| 5 | **한도 공식** — `자본총계 × equityLimitRatio(기본 10%) × gradeFactor(A 1.0 / B 0.8 / C 0.6 / D 0.3 / E 0)`. 자본총계가 null 이거나 ≤0 이면 한도 0 | `CorporateCreditPolicy.java:27-28` |
| 6 | **수수료 공식** — `원금 × dailyRate × termDays × gradeSurcharge(A 1.0 / B 1.1 / C 1.25 / D 1.5)` | `CorporateCreditPolicy.java:29` |
| 7 | **모든 매핑은 구간 기반 결정적 함수** — 단위 테스트로 경계를 고정할 수 있게 설계됐다 | `CorporateCreditPolicy.java:14` |
| 8 | **자체 원장은 차1·대1 균형** — 한 전표 안에서 차변금액 = 대변금액이며 팩토리로만 생성 | `domain/LoanLedgerEntry.java:12,21-38` |
| 9 | **전표 팩토리가 계정 조합을 고정** — 실행 `LOAN_RECEIVABLE/CASH`, 수수료 `FEE_RECEIVABLE/FEE_INCOME`, 상환 `CASH/LOAN_RECEIVABLE` | `LoanLedgerEntry.java:45,50,55,61,67` |
| 10 | **상환 충당은 가용액 한도 내에서** — 음수 가용액은 타입 예외, 충당액은 `min(잔액, 가용액)` | `domain/LoanAdvance.java:132-142` (`LoanInvariantViolationException`) |
| 11 | **물건금융은 잔가 허용/필수를 타입이 구분** — `residualAllowed`·`residualRequired` 를 enum 이 들고 있다 | `domain/AssetFinanceType.java:17,29-35,45` |

## 이벤트 계약

**발행 6+** — `LoanDisbursementRequested` · `LoanRepaymentApplied` · `CorporateLoanDisbursed` ·
`SecuredLoanDisbursed` · `SecuredLoanRepaid` · `SecuredLoanPrincipalRepaid` · `LeaseActivated` (Outbox 경유).

**소비 3** — `settlement.created` · `settlement.confirmed`(선정산 재원) · `company.reputation_changed`(신용평가 입력).

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 상태머신 2종 전이표·타입 예외가 일치한다 | `./gradlew :loan-service:test` — 상태 enum·애그리거트 테스트 |
| AC-2 | 등급 구간·한도·수수료 경계값이 정본 표와 일치한다 | `CorporateCreditPolicy` 테스트 (경계 전수) |
| AC-3 | E 등급이 대출 불가로 차단된다 | `CorporateCreditPolicy` 테스트 |
| AC-4 | 원장 전표가 차1·대1 균형이며 팩토리 밖 생성 경로가 없다 | `LoanLedgerEntry` 테스트 |
| AC-5 | 상환 충당이 가용액을 넘지 않는다 | `LoanAdvance` 테스트 |
| AC-6 | 발행 이벤트가 JSON Schema 계약과 일치한다(양방향) | 계약 테스트 + `account-service` 소비측 |
| AC-7 | 소비 컨슈머가 DLT 배선에 닿는다 | `scripts/harness/guard.mjs` KAFKA-DLQ |
| AC-8 | 커버리지 LINE >= 90% | `./gradlew :loan-service:jacocoTestCoverageVerification` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1** **한 서비스에 상품 4종**(선정산·기업신용·담보·물건금융)이 들어 있다. 상태머신·원장·정책이
  상품마다 따로여서 도메인 클래스가 24개를 넘는다. 배포 단위를 쪼갤지는 판단이 필요하지만 그 판단 근거가
  문서에 없다. → `disposition: recorded-not-fixed` (경계 후보)
- **KI-2** **`lemuel.loan.lease_activated` 의 소비처가 없다** — 계약 스키마와 발행은 있는데 받는 쪽이
  아직 배선되지 않았다(`SPEC.md` §5 에 "소비처 미배선"으로 기재). 리스 개시가 GL 에 반영되지 않는다.
  → `disposition: gap` (배선 미완)
- **KI-3** 신용점수 입력이 **외부 2소스(재무제표·뉴스 평판)에 의존**한다. 두 소스가 모두 비어 있을 때
  등급이 어떻게 되는지(E 로 떨어지는지, 산정 자체를 거부하는지)는 이 Seed 범위에서 확인하지 않았다.
  → `disposition: recorded-not-verified`
- **KI-4** 자체 원장(`LoanLedgerEntry`)과 전사 GL(account-service)이 **이중 기록**된다. loan 이 자기 원장에
  적고, 같은 사건을 이벤트로 보내 account 가 다시 분개한다. 두 원장이 어긋났을 때 무엇이 정본인지가
  문서에 없다. → `disposition: recorded-not-verified` (대사 기준 불명)
