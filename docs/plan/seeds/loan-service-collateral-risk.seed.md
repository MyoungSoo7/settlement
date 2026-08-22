# Seed — loan-service 담보 재평가·마진콜·실행 as-is 사양

> **상태: CONFIRMED** (역산 결정화, 2026-08-22) · 정본 데이터: [`loan-service-collateral-risk.seed.yaml`](loan-service-collateral-risk.seed.yaml)
> 자매 Seed: [loan-service 여신 코어](loan-service-lending.seed.md) — 그 Seed 가 제외에 적어 둔
> "담보 재평가·마진콜 상세(`Collateral*`·`MarginCall*` — 별도 Seed 대상)"가 이 문서다.
>
> **as-is 원칙** — 현행 코드가 실제로 하는 일의 불변 기술이다. 결함은 교정하지 않고 Known Issues 로만 기록한다.

## Goal (한 줄)

**loan-service 담보 리스크 관리(재평가 → 마진콜 판정 → 강제 처분·대위변제·상각)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 임계 정책의 드리프트 방지 ·
면접/포트폴리오 문서로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| 담보·마진콜 상태머신 2종과 대출 상태머신과의 분리 | 여신 4종의 심사·한도·수수료(→ 여신 코어 Seed) |
| 유지비율 임계(140%/120%)와 교차곱 판정 | 담보 문서 OCR 대조(`CollateralDocument*` — 인접 기능, 별도 관심사) |
| 담보 유형 계열(Category)이 가르는 행위 | 연체·기한이익상실 전이(`SecuredLoanCollectionService` 소관) |
| 실행 3경로(처분·대위변제·상각)와 손실 계정 분리 | 위성 시세 어댑터의 외부 연동 상세 |
| 재평가 이력 append-only 규약 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

> 경로 접두: `loan-service/src/main/java/github/lms/lemuel/loan/`

1. **담보 생명주기는 대출과 독립이다.** `PLEDGED → ACTIVE → RELEASED` (+ 심사 거절 시
   `PLEDGED → RELEASED`) — 담보는 대출보다 먼저 설정되고 완제 후에 말소되므로 두 생명주기가
   1:1로 겹치지 않는다 (`domain/CollateralStatus.java:15-20,26-38`).

2. **마진콜도 대출 상태에 녹이지 않는다.** `OPEN → RESOLVED | ESCALATED`, 둘 다 종료 상태
   (`domain/MarginCallStatus.java:17-22,26-37`). **대출 1건에 여러 번** 발생할 수 있고 각 발생이
   독립적으로 해소·이관되기 때문이다 — 대출 상태에 녹이면 이력이 덮여 **어느 시점에 몇 번 부족했는지
   재현할 수 없다**(주석 명문).

3. **전이표는 enum 이 단일 출처**다. `Collateral`·`MarginCall` 의 전이 가드가 각 enum 의
   `canTransitionTo` 에 위임하므로, 표에 없는 전이는 애그리거트에서도 금지된다
   (`Collateral.java:133` · `MarginCall.java:88` → `InvalidLoanStateException`).

4. **담보 유형은 파라미터가 아니라 계열로 나뉜다.** 예금 95% vs 주식 60%처럼 인정비율이 크게 다른데
   이를 한 유형 안의 파라미터로 두면 **인정비율이 담보 행마다 달라져 정책이 데이터로 새어 나간다**
   (`domain/CollateralType.java:6-13`). 대신 계열(`Category`)이 **행위**를 가른다:

   | 유형 | 계열 | 인정비율 | 마진콜 | 처분 | 대위변제 |
   |---|---|---|---|---|---|
   | REAL_ESTATE | REAL_ESTATE | 주입값(`app.loan.secured.real-estate-ltv`, 기본 0.70) | ✗ | ✓ | ✗ |
   | GUARANTEE | GUARANTEE | 1.00 | ✗ | ✗ | ✓ |
   | DEPOSIT | FINANCIAL_ASSET | 0.95 | ✓ | ✓ | ✗ |
   | BOND | FINANCIAL_ASSET | 0.80 | ✓ | ✓ | ✗ |
   | EQUITY | FINANCIAL_ASSET | 0.60 | ✓ | ✓ | ✗ |

   (`SecuredLoanPolicy.java:62-68` · `CollateralType.java` 계열 술어)

5. **마진콜은 금융자산 계열만 해당한다.** 주택담보는 시세가 떨어져도 추가담보를 요구하지 않는
   상품이라 **재평가 이력만 남기고 조치는 없다** (`application/service/RevalueCollateralService.java:74-79`).

6. **유지비율 임계는 140%(마진콜)·120%(청산)이며 청산 구간이 마진콜 구간을 포함한다**
   (`domain/SecuredLoanPolicy.java:53,55,257-265`).

7. **임계 판정은 나눗셈이 아니라 교차곱이다.** `coverageRatio` 는 표시용이고 판정에 쓰지 않는다 —
   scale 2 로 절사하면 `139,999,999 / 100,000,000` 이 **1.40 으로 올라붙어 임계를 통과해 버리기**
   때문이다. `requiresMarginCall`·`requiresLiquidation` 은 `유효담보가치 < 잔액 × 임계` 로 비교한다
   (`SecuredLoanPolicy.java:245-248,276-280`).

8. **잔액 0이면 담보력을 따지지 않는다** — `belowThreshold` 가 즉시 false 를 돌려준다
   (`SecuredLoanPolicy.java:281-283`).

9. **유효담보가치 = max(0, 평가액 − 선순위)**. 재평가 경로는 `Collateral#effectiveValue()` 를 쓰지 않고
   같은 규칙을 **최신 시가에 다시 적용**한다 — 담보 엔티티의 값은 설정 시점 스냅샷이기 때문이다
   (`Collateral.java:120-122` vs `RevalueCollateralService.java:125-134` 주석 명문).

10. **재평가는 이력 행으로만 쌓이고 설정 시점 평가액을 덮지 않는다.** 덮으면 그 대출의 한도 산정
    근거가 사후에 바뀌어 **재현이 불가능해진다** (`RevalueCollateralService.java:27-29`).

11. **재평가와 판정은 한 트랜잭션이다.** 시가를 새로 안 시점이 곧 조치를 결정해야 하는 시점이라,
    기록만 하고 판정을 미루면 그 사이 담보 부족이 방치된다 (`RevalueCollateralService.java:24-26`).

12. **판정은 비관적 락으로 직렬화한다** — `findByIdForUpdate` 로 같은 부족 상황에 마진콜이 두 번
    열리지 않게 한다 (`RevalueCollateralService.java:62-65`).

13. **이미 열린 마진콜이 있으면 새로 열지 않고 요구액은 최초 발생 시점 값을 보존한다**
    (`RevalueCollateralService.java:100-103`). 요구액이 재평가마다 흔들리면 채무자가 무엇을 채워야
    하는지가 바뀐다.

14. **청산선 미달이어도 마진콜을 먼저 연다.** 열린 것이 없으면 열고 **즉시 `ESCALATED` 로 이관 표시**한다 —
    이관 사실을 남기지 않고 대출만 부도 처리하면 **"왜 처분했는지"의 근거가 사라진다**
    (`RevalueCollateralService.java:88-96`).

15. **재평가 서비스는 대출 상태를 바꾸지 않는다.** 연체·기한이익상실 전이는
    `SecuredLoanCollectionService` 의 몫이다 — 마진콜 판정이 대출 상태머신을 직접 건드리면 두 생명주기가
    결합되고 **배치가 실수로 대출을 부도 처리하는 사고 경로가 생긴다**
    (`RevalueCollateralService.java:31-34` 주석 명문).

16. **유지비율이 회복되면 열린 마진콜이 자동 해소된다** (`RevalueCollateralService.java:107-111`).

17. **마진콜 요구액은 양수만 성립한다** — 0 이하면 "담보가 충분하다"는 뜻이라 마진콜 대상이 아니다
    (`domain/MarginCall.java:53-56` → `LoanInvariantViolationException`).

18. **실행은 기한이익상실(DEFAULTED) 이후에만 가능하다.** 그 전에는 아직 회수 가능한 채권이라
    상각 전이가 도메인 상태머신에서 막힌다 (`application/service/EnforceCollateralService.java:25-27`).

19. **실행 경로는 담보 계열이 강제한다** — 처분은 `supportsDisposal()`, 대위변제는 `supportsSubrogation()`
    을 통과해야 한다 (`EnforceCollateralService.java:73-77,110-114`). 보증부를 처분 경로로,
    금융자산을 대위변제 경로로 밀어넣을 수 없다.

20. **손실 계정을 경로별로 나눈다 — 이중 인식 방지.**
    처분 부족분 → `securedDisposalShortfall`(담보물을 실제로 처분한 결과),
    대위변제 미보증분 → `securedWriteOff`(처분 없이 회수 불능 확정).
    **두 전표를 같이 쓰면 손실이 이중 인식되므로 경로마다 하나만 기표한다**
    (`EnforceCollateralService.java:28-31,97-101,125-129`).

21. **처분 초과금(surplus)은 별도 전표로 남긴다** — `proceeds − 실행 전 잔액` 이 양수일 때만
    `securedDisposalSurplus` (`EnforceCollateralService.java:88-93`). 회수는 `loan.repay` 가
    잔액을 넘어 차감되지 않도록 클램프한다.

22. **보증부 회수는 보증비율만큼이다** — 보증기관 부담 85%, 나머지 15%는 우리 신용리스크
    (`SecuredLoanPolicy.java:49-50` `GUARANTEE_COVERAGE_RATIO`), 미보증분은 대손으로 인식한다.

23. **원금 감소는 건별로 발행한다.** 완제 이벤트만으로는 계정계가 기중 잔액을 모르기 때문에
    처분·대위변제 회수마다 `publishPrincipalRepaid(loan, recovered, "COLLATERAL_DISPOSAL"|"SUBROGATION")`
    을 발행한다 (`EnforceCollateralService.java:84-87,120-123`).

24. **실행이 끝나면 담보를 말소한다** — 이미 `RELEASED` 가 아니면 `release()` 후 저장
    (`EnforceCollateralService.java:132-137`).

25. **동시 실행도 비관적 락으로 막는다** — 동시 처분 요청이 들어와도 회수·상각 전표가 중복되지 않는다
    (`EnforceCollateralService.java:33`).

## 진입 표면 (도달성 확인 완료)

| 경로 | 유스케이스 | 구현 |
|---|---|---|
| `POST /loans/secured/{loanId}/collateral/revalue` | `RevalueCollateralUseCase` | `RevalueCollateralService` |
| `POST /loans/secured/{loanId}/collateral/dispose` | `EnforceCollateralUseCase#dispose` | `EnforceCollateralService` |
| `POST /loans/secured/{loanId}/collateral/subrogate` | `EnforceCollateralUseCase#subrogate` | 동상 |

`adapter/in/web/CollateralController.java:44,62,74,91` — **세 경로 모두 인바운드 어댑터가 유스케이스를
실제로 호출한다**(grep 실측). 2026-08-13 역산 시점에는 이 컨트롤러가 없어 마진콜 140%·청산 120% 판정이
**도달 불가**였다 — 그때는 정책 상수·서비스·단위테스트만 있었다. 지금은 배선돼 있다.

## 데이터

| 마이그레이션 | 담는 것 |
|---|---|
| `V20260730180000__secured_loan_phase2_collateral.sql` | 담보 |
| `V20260730200000__collateral_revaluation_margin_call.sql` | 재평가 이력 · 마진콜 |
| `V20260730210000__secured_loan_write_off_status.sql` | 상각 상태 |
| `V20260730233000__secured_loan_financial_asset_product.sql` | 금융자산 담보 상품 |

## 이벤트 계약

**발행** — 실행 회수 시 `lemuel.loan.secured_loan_principal_repaid`(건별 원금 감소).
완제 시 `lemuel.loan.secured_loan_repaid`. 실행(disbursement) 계열은 여신 코어 Seed 소관.
**소비 0** — 담보 리스크는 외부 이벤트를 듣지 않는다(KI-1 과 직결).

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 140%/120% 임계 판정이 교차곱으로 절사 오차 없이 동작한다 | `SecuredLoanPolicyTest` · `RevalueCollateralServiceTest` |
| AC-2 | 금융자산 계열만 마진콜 대상이다 | `RevalueCollateralServiceTest` · `CollateralTest` |
| AC-3 | 열린 마진콜이 있으면 중복 생성되지 않고 요구액이 보존된다 | `RevalueCollateralServiceTest` |
| AC-4 | 청산 구간에서 마진콜이 열린 뒤 ESCALATED 로 이관된다 | `RevalueCollateralServiceTest` · `MarginCallTest` |
| AC-5 | 유지비율 회복 시 열린 마진콜이 해소된다 | `RevalueCollateralServiceTest` |
| AC-6 | 상태 전이표 밖 전이가 거부된다 | `CollateralStatusTest` · `MarginCallTest` |
| AC-7 | 처분·대위변제가 계열 술어로 강제된다 | `EnforceCollateralServiceTest` |
| AC-8 | 처분손실과 대손이 같은 실행에서 이중 인식되지 않는다 | `EnforceCollateralServiceTest` |
| AC-9 | 선순위 차감이 유효담보가치에 반영된다 | `CollateralSeniorClaimTest` |
| AC-10 | 커버리지 LINE >= 90% | `./gradlew :loan-service:jacocoTestCoverageVerification` |

**테스트 자산**: 담보·마진콜 관련 15개 클래스(문서 OCR 6종 포함).

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1 ★ 재평가를 아무도 자동으로 부르지 않는다.** loan-service 의 `@Scheduled` 는
  `LoanOverdueScheduler`(연체 스캔, 매일 03:00)와 `PartitionMaintenanceRunner` 2건뿐이고 **담보
  재평가 배치가 없다**. 소비 토픽도 0이다. 즉 **시가는 계속 움직이는데 아무도 묻지 않고**,
  누군가 `POST .../revalue` 를 손으로 부를 때까지 담보 부족이 발견되지 않는다.
  마진콜·청산 판정 로직 자체는 배선돼 있으나(위 표) **주기적 발동 경로가 없다**.
  → `recorded-not-fixed` (도달성 격차 — `@Scheduled` grep 실측)

- **KI-2 ★ 재평가액을 호출자가 준다.** `revalue(loanId, revaluedValue, source)` 는 시가를
  **요청 본문에서 받는다**. 위성 시세 어댑터(`SatelliteCollateralValuationAdapter`,
  `CollateralValuationPort`)는 존재하지만 **설정 시점 평가(`RequestSecuredLoanService`)에서만** 쓰이고
  재평가 경로에는 연결돼 있지 않다. 결과적으로 마진콜을 촉발하는 값이 시장이 아니라 **운영자 입력**이며,
  `source` 는 자유 문자열이라 출처가 강제되지 않는다.
  → `recorded-not-fixed` (KI-1 과 한 쌍 — 자동 재평가를 붙일 때 함께 결정할 사항)

- **KI-3 담보 없는 대출 재평가와 처분 매각대금 검증이 generic `IllegalArgumentException` 이다**
  (`RevalueCollateralService.java:70` · `EnforceCollateralService.java:67,75,112`).
  loan 도메인은 `LoanInvariantViolationException` 계열을 쓰는데 이 네 곳만 타입이 다르다 —
  금융 5서비스 도메인의 generic IAE 금지 규칙은 `domain/` 패키지에 걸려 있어 `application/service/`
  는 게이트에 걸리지 않는다.
  → `recorded-not-fixed` (일관성 격차)

- **KI-4 `coverageRatio` 는 표시 전용인데 반환 타입이 판정값과 구분되지 않는다.** 불변식 7의 이유로
  판정에 쓰면 안 되지만, 같은 `BigDecimal` 이라 호출자가 임계와 직접 비교해도 컴파일된다.
  주석(`SecuredLoanPolicy.java:245-248`)이 유일한 방어다.
  → `recorded-not-fixed` (타입으로 못 박을 후보)

- **KI-5 마진콜 이관(ESCALATED)이 아무 사건도 알리지 않는다.** 이관은 DB 행 상태로만 남고
  이벤트 발행도 알림도 없다 — 강제 처분 단계로 넘어간 사실을 알려면 누군가 조회해야 한다.
  → `recorded-not-fixed` (notification-service 연계 후보)
