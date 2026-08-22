# Seed — settlement-service 세무 as-is 사양 (부가세·원천징수·세금계산서 스캔 대사)

> **상태: CONFIRMED** (역산 결정화, 2026-08-22) · 정본 데이터: [`settlement-service-tax.seed.yaml`](settlement-service-tax.seed.yaml)
> 자매 Seed: [settlement-service 회계 코어](settlement-service-accounting-core.seed.md) — 그 Seed 는
> `tax` 를 **선언된 범위 밖**에 두었다(감사 R-2). 이 문서가 그 자리를 채운다.
>
> **as-is 원칙** — 결함은 교정하지 않고 Known Issues 로만 기록한다.

## Goal (한 줄)

**settlement-service 세무 슬라이스(부가세 포함과세 분리·개인 셀러 원천징수·세금계산서 발행과
스캔본 대사)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 ·
세무 항등식 드리프트 방지 · 면접/포트폴리오 문서로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| `TaxCalculation` 항등식 3종과 자가검증 | 정산 생성·수수료·홀드백(→ 회계 코어 Seed) |
| 세무 전용 라운딩(원단위 절사)과 `Money` 와의 분리 이유 | 국세청 실연동(발행은 내부 번호 체계) |
| 셀러 세무 프로파일(개인/사업자)과 미등록 처리 | OCR 제공자 내부 |
| 세금계산서 발행번호 규칙과 왕복 검증 | 월마감·정합성 스위트(별도 Seed) |
| 스캔 상태머신 5상태와 대사 판정 순서 | |
| 원천징수의 실제 지급 공제 경로 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

> 경로 접두: `settlement-service/src/main/java/github/lms/lemuel/tax/`

### 계산

1. **부가세는 포함과세다** (ADR 0029, `domain/TaxCalculation.java:16-18`):
   ```
   vatAmount    = floor(commission × 10/110)     ← 수수료가 부가세 포함 총액
   supplyAmount = commission − vatAmount          ← 세금계산서 공급가액
   ```

2. **과거의 외부과세 모델은 결함이었고, 그 이유가 코드에 남아 있다.** `commission × 0.10` 을 별도
   청구로 인식하던 모델은 **실제로 청구되지 않는 미수금을 무한히 쌓았다** — 부가세는 수수료 안에서
   갈라내는 것이지 별도로 받는 게 아니다 (`TaxCalculation.java:22-25`).

3. **원천징수는 개인 셀러만, 3.3%** (소득세 3% + 지방소득세 0.3%):
   `withholdingAmount = 개인 ? floor(netAmount × 0.033) : 0`, `netPayable = netAmount − withholdingAmount`
   (`TaxCalculation.java:19-20,44`). 사업자에게 원천징수가 붙으면 항등식 자가검증이 거부한다
   (`TaxCalculation.java:141`).

4. **원천징수는 장부만이 아니라 실제 지급에서 공제된다.** payout 금액 =
   `immediate − withholding − offset` (차감 순서 T-4 로 확정,
   `settlement/adapter/in/batch/confirm/SettlementConfirmItemWriter.java:29`).
   **과거엔 세무 전표(장부)만 줄이고 실제 송금은 전액 나갔다** — 독립 GL 감사 HIGH #4 로 봉합됐다
   (`TaxCalculation.java:26-29`).

5. **산출 직후 항등식을 자가검증한다** (`TaxCalculation.java:122-142`) — 네 가지가 구성적으로 확인된다:
   - 세무 예수금 음수 금지
   - **포함과세 항등식**: `공급가액 + 세액 = commission`
   - **실지급액 항등식**: `netPayable = netAmount − withholdingAmount`
   - 원천징수액이 순정산액을 초과 금지 · 사업자 원천징수 금지

6. **세무 라운딩은 `Money` VO 와 의도적으로 분리한다.** 공용 `Money` 는 scale 2 HALF_UP 인데,
   그것을 통과시키면 **반올림이 먼저 개입해 세무 절사 의미가 손상된다**. 세무 금액은 곱셈 원값에
   `TaxRounding.floorToWon`(scale 0, `DOWN`)을 직접 적용한다 (`domain/TaxRounding.java:11-18`).
   세무 금액은 음수가 아니므로 `DOWN` 은 곧 `floor` 와 동치이며, 음수 입력은 예외로 거부한다.

7. **중간 나눗셈 스케일은 10이고 그 이유가 명시돼 있다** — 이후 `floorToWon` 이 0 스케일로 절사하므로
   스케일이 충분히 크고 `DOWN` 방향이 같기만 하면 최종 결과가 동일하다 (`TaxCalculation.java:38-40`).

### 발행과 대사

8. **발행번호는 정산 식별자에서 결정적으로 파생된다** — `TaxInvoice.numberFor(settlementId)`.
   번호 형식의 권위는 `TaxInvoice` 한 곳에 남는다 (`domain/scan/TaxInvoiceScanMatcher.java:11-14`).

9. **되읽기는 왕복 일치일 때만 인정한다.** 스캔본 승인번호에서 숫자만 추출해 정산 식별자 후보를 만들고,
   **그 후보로 다시 만든 발행번호가 원문과 똑같아야** 우리 번호로 인정한다 — 타사 계산서 번호로
   우연히 매칭되는 것을 막는다 (`TaxInvoiceScanMatcher.java:25-50`).

10. **IDOR 방어가 금액 대사보다 앞선다.** 후보 발행분의 소유 셀러가 업로더와 다르면 **금액이 아무리
    같아도 대사하지 않는다** — 남의 계산서 번호를 적어 올려 타인의 정산 정보를 확인하는 경로를 막는다
    (`TaxInvoiceScanMatcher.java:16-18`).

11. **스캔 상태머신 5상태, 전이표가 정본**이다 (`domain/scan/TaxInvoiceScanStatus.java:12-20`):
    ```
    EXTRACTED  → MATCHED | MISMATCHED | UNMATCHED | REJECTED
    MISMATCHED → MATCHED | REJECTED        (재대사·반려)
    UNMATCHED  → MATCHED | REJECTED        (재대사·반려)
    MATCHED    → (종결)   REJECTED → (종결)
    ```

12. **`MISMATCHED ↔ UNMATCHED` 를 서로 오갈 수 없게 한 것은 의도적이다** — 대사 결과를 번복하는
    경로는 "다시 대사해서 MATCHED" 또는 "반려" 둘뿐이어야 **조사 이력이 남는다**
    (`TaxInvoiceScanStatus.java:21-22`).

13. **`MATCHED` 는 반려조차 불가능한 종결이다** — 그래서 **자동 진입은 보수적이어야 한다**
    (`TaxInvoiceScanStatus.java:35`).

14. **저신뢰 판독은 `EXTRACTED` 에 남는다.** `needsReview` 인 스캔은 자동 대사를 건너뛴다 —
    **믿을 수 없는 값으로 `MATCHED`(종결)나 `UNMATCHED`("발행분을 못 찾았다") 같은 결론을 기록하지
    않기 위해서다.** 확정은 관리자가 재대사를 눌러야 일어난다 (`TaxInvoiceScanStatus.java:26-33`).
    → 이것이 `card` 의 `NEEDS_REVIEW` 와 같은 결의 판단이다(자매 규칙).

15. **세무 예외는 타입 계열이다** — `TaxDomainException` 아래 `TaxInvariantViolationException` ·
    `TaxInvoiceScanStateException` · `SellerTaxProfileNotRegisteredException`. generic IAE 를 쓰지 않는다.

16. **세무 프로파일 미등록은 조용히 넘어가지 않는다** — 확정 배치가
    `withholding.profileRegistered()` 를 확인하고 `settlement.withholding.shortfall` 메트릭으로
    노출한다 (`SettlementConfirmItemWriter.java:43,92-94`).

### 이벤트

17. **`lemuel.settlement.withholding_accrued` 를 확정 시점에 발행해 account GL 이 예수금을 인식한다**
    (`SettlementConfirmItemWriter.java:31` · `tax/domain/TaxJournal.java`).

## 인터페이스

| 표면 | 경로 | 용도 |
|---|---|---|
| 셀러 | `/api/tax-invoices/settlement/{settlementId}` | 자기 정산의 세금계산서 조회 |
| 셀러 | `/api/tax-invoices/scans` | 스캔본 업로드·조회 |
| ADMIN | `/admin/tax/scans` | 스캔 리뷰 큐 — 재대사·반려 |
| ADMIN | `/admin/tax/settlements/{settlementId}` | 정산 1건의 세무 산출·대사 조회 |
| ADMIN | `/admin/seller-tax-profiles` | 셀러 개인/사업자 프로파일 관리 |

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | 포함과세 항등식 `공급가액 + 세액 = commission` 이 성립한다 | `TaxCalculationTest` |
| AC-2 | 실지급액 항등식 `netPayable = netAmount − withholding` 이 성립한다 | `TaxCalculationTest` |
| AC-3 | 사업자에게 원천징수가 붙지 않는다 | `TaxCalculationTest` — `TaxInvariantViolationException` |
| AC-4 | 세무 금액이 원단위 절사(`DOWN`)로 산출된다 | `TaxRoundingTest` |
| AC-5 | 원천징수가 실제 payout 금액에서 공제된다 | `ResolveSettlementWithholdingServiceTest` · `SettlementTaxDeliverablesIntegrationIT` |
| AC-6 | 타사 번호가 왕복 검증에서 탈락한다 | `TaxInvoiceScanMatcherTest` |
| AC-7 | 남의 발행분과는 금액이 같아도 대사되지 않는다(IDOR) | `TaxInvoiceScanMatcherTest` |
| AC-8 | 저신뢰 판독이 `MATCHED`·`UNMATCHED` 로 종결되지 않는다 | `TaxInvoiceScanMatcherTest` · `TaxInvoiceScanTest` |
| AC-9 | 상태 전이표 밖 전이가 거부된다(`MISMATCHED ↔ UNMATCHED` 포함) | `TaxInvoiceScanTest` · `TaxInvoiceScanIntegrationIT` |
| AC-10 | 모든 인바운드 포트가 어댑터에서 도달 가능하다 | `InboundPortReachabilityTest` (ArchUnit) |
| AC-11 | 세무 대사 항목별 차이가 산출된다 | `TaxReconciliationTest` · `TaxReconciliationCheckTest` · `TaxReconciliationServiceTest` |
| AC-12 | 커버리지 LINE >= 90% | `./gradlew :settlement-service:jacocoTestCoverageVerification` |

**테스트 자산**: 세무 슬라이스 29개 파일(단위·슬라이스 27 + Testcontainers 통합 2 —
`SettlementTaxDeliverablesIntegrationIT`·`TaxInvoiceScanIntegrationIT`).

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1 발행번호가 정산 식별자에서 결정적으로 파생된다.** 왕복 검증(불변식 9)이 타사 번호를 막는
  근거이자, **정산 ID 를 알면 발행번호를 계산할 수 있다**는 뜻이기도 하다. 대사 자체는 IDOR 대조로
  막히지만(불변식 10) 번호 자체는 추측 가능한 값이다.
  → `by-design-documented` (트레이드오프)

- **KI-2 `MATCHED` 종결의 되돌림 경로가 없다.** 잘못 대사된 건은 상태로는 정정할 수 없고
  (전이표에 `MATCHED →` 가 비어 있다) 새 스캔을 올리는 우회만 남는다. 보수적 자동 진입(불변식 13)이
  그 대가로 얻은 것이지만, **오판정 시 조치 절차가 문서화돼 있지 않다**.
  → `recorded-not-fixed` (러너북 후보)

- **KI-3 세무 프로파일 미등록은 메트릭으로만 드러난다.** `settlement.withholding.shortfall` 이
  올라가도 **정산은 진행된다** — 개인 셀러인데 프로파일이 없으면 원천징수 없이 전액이 나간다.
  알림·차단 경로가 아니라 관측 지표다.
  → `recorded-not-fixed` (정책 결정 사항)

- **KI-4 국세청 실연동이 없다.** 발행은 내부 번호 체계이고 외부 전송·승인 경로가 없다.
  대사는 "우리가 발행했다고 기록한 것"과 "셀러가 올린 스캔본" 사이에서만 이뤄진다.
  → `by-design` (범위 — 실연동 시 승인번호 권위가 국세청으로 옮겨간다)

- **KI-5 `tax` 슬라이스가 회계 코어 Seed 의 범위 밖이라 두 Seed 의 경계가 항등식 위에서 만난다.**
  `netPayable` 은 이 Seed 가, `net`·`holdback` 은 회계 코어가 정의한다 — 둘 중 하나만 바뀌면
  `SettlementConfirmItemWriter` 의 차감 순서(T-4)가 조용히 어긋난다. 두 Seed 를 함께 읽어야 한다.
  → `recorded-not-fixed` (문서 경계 위험)
