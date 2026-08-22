# Seed — account-service 수신 3종(정기예금·적금·퇴직연금) as-is 사양

> **상태: CONFIRMED** (역산 결정화, 2026-08-22) · 정본 데이터: [`account-service-banking-deposits.seed.yaml`](account-service-banking-deposits.seed.yaml)
> 자매 Seed: [account-service GL 코어](account-service-gl-core.seed.md) — 그 Seed 가 "별도 Seed 대상"으로
> 명시해 둔 공백이 이 문서다. GL 코어가 **소비 전용 집계 원장**이라면, 수신 3종은 같은 배포 단위 안에서
> **자기 거래를 스스로 만드는 상품 도메인**이다.
>
> **as-is 원칙** — 이 Seed 는 현행 코드가 실제로 하는 일의 불변 기술이다. 결함은 교정하지 않고
> Known Issues 로만 기록한다.

## Goal (한 줄)

**account-service 수신 3종(정기예금·적금·퇴직연금)의 현행 동작 — 상태머신·이자 산식·수급 자격·
서브원장↔GL 동시 기표 — 을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 ·
이자 계산 계약의 드리프트 방지 · 면접/포트폴리오 문서로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| 상태머신 3종(TimeDeposit·Savings·Pension)과 종단 상태 규약 | GL 전표 생성 규칙 자체(→ GL 코어 Seed 불변식 1·2) |
| 이자 산식 3종(ACT/365 단리·월복리·회차 일수가중)과 반올림 규약 | 이벤트 소비 27토픽 매핑(→ GL 코어 Seed) |
| 퇴직연금 제도(DB·DC·IRP) 속성 규칙과 수급 자격 | 세제(연금소득세·이자소득세) — 코드에 없다 |
| 소유권 대조(IDOR)와 운영자 경로의 비대칭 | 시산표·통제계정 대사 조회 표면(→ GL 코어 Seed) |
| REST 표면 19엔드포인트와 인가 등급 분리 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

> 경로 접두: `account-service/src/main/java/github/lms/lemuel/account/banking/`

1. **상태머신은 전부 단방향·종단**이다. 되살아나는 전이가 없다.
   - TimeDeposit: `ACTIVE → CLOSED` 2상태 (`timedeposit/domain/TimeDepositStatus.java:12,15`).
     **만기해지와 중도해지가 같은 상태를 쓴다** — 갈리는 것은 상태가 아니라 적용 이율뿐이다.
   - Savings: `ACTIVE → CLOSED` 2상태 (`savings/domain/SavingsStatus.java:11-12`). 같은 이유로 2상태다 —
     상태를 둘로 쪼개면 "해지된 계약인가?"를 묻는 모든 지점이 두 값을 알아야 한다(주석 명문).
   - Pension: `ACCUMULATING → RECEIVING → CLOSED` 3상태 (`pension/domain/PensionStatus.java:12,15,18`).
     적립 중에만 부담금·중도인출이, 수급 개시 후에는 급여 지급만 성립한다.

2. **이자는 확정 시점이 하나뿐이다(정기예금).** 개설 시점에 확정하지 않고 만기·중도해지 때 단 한 번
   `settledInterest` 로 굳혀 `payoutAmount = principal + settledInterest` 를 지급한다
   (`timedeposit/domain/TimeDeposit.java:104-125`). **주기 accrual 테이블도 이자 스케줄러도 없다** —
   확정 지점이 하나면 스케줄러 누락·중복 실행으로 이자가 새는 사고 자체가 성립하지 않는다.

3. **만기·중도해지는 같은 산식에 다른 이율만 꽂는다.** `close(closingDate, appliedRate)` 하나를
   `closeOnMaturity`(=`annualRate`)와 `closeEarly`(=`earlyTerminationRate`)가 공유한다
   (`TimeDeposit.java:104,109,113`). 산식이 한 곳이라 두 경로가 영원히 갈라질 수 없다.

4. **일수 기준 ACT/365 고정** — 분모는 언제나 상수 365다(윤년에도 366 을 쓰지 않는다).
   `TimeDepositInterest.java:31` · `InstallmentSavingsInterest.java:41` · `RetirementPension.java:59`
   세 곳 모두 같은 상수를 쓴다.

5. **반올림 규약 3조** (`TimeDepositInterest.java:16-27` 주석이 계약으로 명문화):
   - 전 구간 `BigDecimal` — `double` 금지. 이진 부동소수 오차는 원 단위로 내려와 GL 수신부채가
     0 으로 안 닫히는 사고가 된다.
   - **중간 나눗셈은 반드시 스케일·반올림 명시**(scale 10, HALF_UP). 무지정 `divide` 는 1/3 같은
     무한소수에서 `ArithmeticException` 이라 이율 하나 바꿨다고 런타임이 죽는다.
   - **최종 이자는 원 단위(scale 0) HALF_UP**, 그리고 **합계에 딱 한 번만** 적용한다
     (`InstallmentSavingsInterest.java:33-36`). 회차마다 반올림하면 회차 수만큼 오차가 누적돼
     예금주에게 유·불리로 표류한다.

6. **적금 이자는 회차별 일수 가중이다** — `Σ_i (paidAmount_i × rate × daysHeld_i / 365)`,
   `daysHeld_i` = 그 회차의 **실제 납입일 → 만기(또는 중도해지)일** (`InstallmentSavingsInterest.java:14-21`).
   원금 하나가 통째로 예치되는 정기예금과 산식이 다른 이유가 여기에 있다.

7. **연체에 페널티 계수가 없다.** 연체는 만기일을 밀지 않고, 늦어진 `paidOn` 만큼 그 회차의
   `daysHeld` 가 자동으로 줄어 이자만 감소한다 (`InstallmentSavingsInterest.java:26-29`).
   계산기는 연체를 특별 취급하지 않는다 — 실제 납입일만 보면 효과가 이미 반영돼 있다.

8. **월복리의 자투리 단리는 복리 적립 후 잔액에 붙는다** — 최초 원금이 아니다
   (`TimeDepositInterest.java:81-105`). 자투리 시작일이 `openedOn.plusMonths(경과개월)` 이라
   월말 보정(1/31 → 2/28)은 `LocalDate` 규칙을 그대로 따른다.

9. **적립 방식이 필드 조합을 배타적으로 강제한다(적금).** 정액적립식에는 회차 한도를 둘 수 없고,
   자유적립식에는 월 약정액을 둘 수 없다 (`savings/domain/InstallmentSavings.java:123-135`).
   둘 다 `InvalidSavingsTermsException` — generic IAE 가 아니다.

10. **제도가 규칙의 축이다(퇴직연금).** 부담금 주체·사업장 필수 여부·중도인출 허용이 모두
    `PensionScheme` 의 **상수 속성**에서 나오고, 애그리게이트는 제도별 if 분기를 갖지 않는다
    (`pension/domain/PensionScheme.java:25-32`):

    | 제도 | 사업장명 | 부담금 주체 | 중도인출 |
    |---|---|---|---|
    | DB(확정급여) | 필수 | EMPLOYER 만 | **불가**(제도적으로) |
    | DC(확정기여) | 필수 | EMPLOYER + EMPLOYEE | 법정 사유 가능 |
    | IRP(개인형) | **받아서도 안 됨** | EMPLOYEE 만 | 법정 사유 가능 |

11. **수급 자격도 상수 속성이다.** `ANNUITY` = 만 55세 **그리고** 가입 10년, `LUMP_SUM` = 만 55세만
    (`pension/domain/BenefitType.java:17-20`). 애그리게이트는 형태를 분기하지 않고
    `minimumAge`·`minimumSubscribedYears` 두 값만 비교한다.

12. **운용수익 확정은 금액을 인자로 받지 않는다.** `settleInterest(on)` 이 계약이율·적립금·경과일수로
    산출하며, 산출 이자가 0원이면 거래도 전표도 만들지 않는다 — `AccountEntry` 팩토리가 0을 거부하기
    때문이다 (`pension/application/service/RetirementPensionService.java:82-99`). 같은 이유로
    정기예금도 경과일수 0 이하 또는 이율 0이면 이자 0을 반환해 전표를 만들지 않는다
    (`TimeDepositInterest.java:61-64`).

13. **서브원장과 GL 은 같은 트랜잭션에서 기표한다** — 이벤트 발행 경로가 아니라
    `RecordAccountEntryUseCase.record()` 직접 호출이다 (`RetirementPensionService.java:77,97,129,140`;
    `savings/.../CloseInstallmentSavingsService.java:83,88`). 수신 3종은 같은 서비스 안에 있으므로
    Outbox 를 경유할 이유가 없다(경유하면 서브원장과 GL 이 갈라지는 창이 생긴다).

14. **GL 자연키는 애그리게이트가 센 seq 로 만든다.** 모든 상태 변경 메서드가 방금 만든
    `PensionTransaction` 을 반환하고, 서비스는 그 `seq` 를 `RP-{pensionId}-{seq}` 자연키로 넘긴다
    (`RetirementPension.java:32-36` 주석 · `RetirementPensionService.java:186-187`).
    **서비스가 seq 를 스스로 세지 않게 하는 것이 이 반환값의 목적이다.**

15. **수신부채 계정 3종은 전부 대변성**이다 — `TIME_DEPOSIT_LIABILITY`·`INSTALLMENT_SAVINGS_LIABILITY`·
    `RETIREMENT_PENSION_LIABILITY` (`../domain/GlAccount.java:48,51,54`). 방향이 enum 에 고정돼
    팩토리가 임의로 뒤집을 수 없다.

16. **금액은 원 단위로 미리 닫는다(퇴직연금).** GL 전표는 소수 2자리를 허용하지만 반올림을 **거부**하므로,
    도메인이 원 단위(scale 0, HALF_UP)로 정규화해 보관한다 (`RetirementPension.java:28-31` 주석).
    서브원장↔GL 드리프트를 원천 차단하는 방식이다.

17. **소유권 대조는 읽은 직후에 한다(IDOR).** `loadOwned` 가 계약을 읽고 **즉시** 소유자를 대조한다
    (`RetirementPensionService.java:157-160`). 가입자 경로 전용이다.

18. **운영자 경로는 소유권을 대조하지 않는다 — 대신 전표 owner 를 계약에서 뽑는다.**
    운용수익 확정·급여 지급은 `SecurityConfig` 가 ADMIN/MANAGER 로 잠근 경로라 호출자 신원으로
    대조하면 운영자가 어떤 계약도 만질 수 없어 경로 자체가 죽는다. 대신 전표의 owner 를 **계약에 적힌
    가입자**에서 뽑아 운영자가 남의 이자를 자기 앞으로 기표시킬 여지를 없앤다
    (`RetirementPensionService.java:83-88,119-123` 주석 명문).

19. **애그리게이트가 불변이다(정기예금).** 모든 필드가 `final` 이라 해지는 상태를 바꾸는 대신
    **닫힌 새 인스턴스**를 만들어 돌려준다 (`TimeDeposit.java:22-24`). 세터가 없으니 "절반만 닫힌"
    중간 상태가 존재할 수 없다.

## 인가 등급 (as-is)

`shared-common/.../SecurityConfig.java:275-279` — `/api/banking/**` 은 기본 `authenticated`,
단 **기관이 돈을 인식·지급하는 두 경로**만 ADMIN/MANAGER 다:

| 경로 | 등급 | 왜 |
|---|---|---|
| `POST /api/banking/pensions/*/interest-settlements` | ADMIN/MANAGER | 가입자에게 열면 임의 증액이 된다 |
| `POST /api/banking/pensions/*/benefit-payments` | ADMIN/MANAGER | 지급 실행 |
| 그 외 `/api/banking/**` | authenticated | 계약 주체가 가입자 본인 |

게이트웨이는 `/api/account/**,/api/banking/**` 한 라우트로 묶어 보낸다
(`gateway-service/src/main/resources/application.yml:74-75`).

## REST 표면 (19엔드포인트)

| 컨텍스트 | 베이스 | 엔드포인트 |
|---|---|---|
| 퇴직연금 | `/api/banking/pensions` | 개설 · 부담금 · 운용수익확정 · 수급개시 · 급여지급 · 중도인출 · 단건/목록 조회 (8) |
| 적금 | `/api/banking/savings` | 개설 · 회차납입 · 만기해지 · 중도해지 · 단건/목록 조회 (6) |
| 정기예금 | `/api/banking/time-deposits` | 개설 · 만기해지 · 중도해지 · 단건/목록 조회 (5) |

## 이벤트 계약

**발행 0 · 소비 0.** 수신 3종은 Kafka 표면이 없다 — 거래가 REST 로 들어와 같은 트랜잭션에서
서브원장과 GL 에 동시에 앉는다(불변식 13). account 서비스 전체의 "발행 금지" 경계
(`AccountArchitectureTest`)가 이 도메인에도 그대로 적용된다.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 만기해지·중도해지가 같은 산식에 다른 이율만 적용한다 | `TimeDepositTest` · `TimeDepositInterestTest` |
| AC-2 | ACT/365·원 단위 HALF_UP·합계 1회 반올림이 지켜진다 | `TimeDepositInterestTest` · `InstallmentSavingsInterestTest` |
| AC-3 | 적금 이자가 회차별 일수 가중으로 계산된다(연체 포함) | `InstallmentSavingsInterestTest` |
| AC-4 | 제도별 부담금 주체·사업장·중도인출 조합이 강제된다 | `PensionSchemeTest` · `RetirementPensionTest` |
| AC-5 | 수급 자격(연령·가입기간) 미달 시 개시가 거부된다 | `RetirementPensionTest` — `BenefitEligibilityNotMetException` |
| AC-6 | 이자 0원이면 GL 전표를 만들지 않는다 | `RetirementPensionServiceTest` · `TimeDepositServiceTest` |
| AC-7 | 남의 계약을 조회·변경할 수 없다(IDOR) | `*ControllerTest` 3종 — `*AccessDeniedException` |
| AC-8 | 도메인 규칙 위반이 타입 예외로 나온다(generic IAE 0) | `PensionDomainExceptionTest` · `guard.mjs` OO-* 규칙 |
| AC-9 | 커버리지 LINE >= 90% | `./gradlew :account-service:jacocoTestCoverageVerification` |

**테스트 자산**: `account-service/src/test/**/banking/` 19개 클래스
(도메인 7 · 서비스 6 · 컨트롤러 3 · 영속 2 · 제도상수 1).

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1 세제가 통째로 없다.** 이자소득세·연금소득세 원천징수가 코드에 존재하지 않아 `payoutAmount` 는
  세전 금액이다. 실제 수신 상품이라면 지급 시점에 원천징수 전표가 한 짝 더 필요하다.
  → `recorded-not-fixed` (범위 밖 — settlement 의 `withholding_accrued` 와 동형 설계가 후보)

- **KI-2 만기 자동해지 경로가 없고, 만기 판정 자체가 죽은 코드다.** `TimeDeposit.isMatured(asOf)`
  (`TimeDeposit.java:127`)의 **프로덕션 호출자가 0건**이다 — 부르는 곳은 `TimeDepositTest.java:266-268`
  세 줄뿐이다. 만기가 지난 계약을 자동으로 닫는 인바운드 어댑터도 없다(`@Scheduled` 는 account 전체에
  `BalanceReconScheduler`·`PartitionMaintenanceRunner` 2건뿐이고 둘 다 banking 이 아니다).
  결과적으로 만기 후에도 누군가 `POST /close` 를 부를 때까지 `ACTIVE` 로 남고, 그 사이
  **`/close-early` 를 부르면 중도해지 이율이 그대로 적용된다** — 도메인은 만기를 알지만 아무도 묻지 않는다.
  → `recorded-not-fixed` (도달성 격차 — 호출 경로 grep 실측 0건)

- **KI-3 퇴직연금 급여 지급에 지급 주기 개념이 없다.** `payBenefit` 은 금액을 받아 적립금에서 차감할 뿐,
  `ANNUITY`(연금)와 `LUMP_SUM`(일시금)의 지급 스케줄 차이가 상태 이후로는 사라진다. 수급 형태는
  개시 자격 판정에만 쓰인다.
  → `recorded-not-fixed`

- **KI-4 수신 상품과 집계 원장이 한 배포 단위에 있다.** GL 코어 Seed 의 KI-3 와 같은 지적이다 —
  "소비 전용 집계"라는 account 의 정체성과 "자기 거래를 만드는 상품 도메인"이 섞여 있다.
  분리하면 GL 은 다시 소비 전용이 되지만, 지금은 서브원장↔GL 동시 기표(불변식 13)가 그 대가로 얻은 것이다.
  → `recorded-not-fixed` (경계 후보)

- **KI-5 적금·정기예금에는 거래 이력 테이블이 비대칭이다.** 퇴직연금은 `PensionTransaction` 으로 거래를
  남기지만(그래서 seq 자연키가 가능하다), 정기예금은 거래 이력 없이 애그리게이트 필드에 확정값만 굳힌다.
  같은 서비스 안에서 감사 가능성의 등급이 다르다.
  → `recorded-not-fixed`
