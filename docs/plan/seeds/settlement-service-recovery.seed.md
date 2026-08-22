# Seed — settlement-service 지급후 회수 as-is 사양 (채권 발생·상계·수기 이관)

> **상태: CONFIRMED** (역산 결정화, 2026-08-22) · 정본 데이터: [`settlement-service-recovery.seed.yaml`](settlement-service-recovery.seed.yaml)
> 자매 Seed: [settlement-service 회계 코어](settlement-service-accounting-core.seed.md) ·
> [settlement-service 세무](settlement-service-tax.seed.md) — `recovery` 는 회계 코어의 선언된 범위 밖이었다(감사 R-2).
>
> **as-is 원칙** — 결함은 교정하지 않고 Known Issues 로만 기록한다.

## Goal (한 줄)

**settlement-service 지급후 회수 슬라이스(이미 송금이 끝난 뒤 발생한 마이너스 조정을 채권으로 세워
후속 정산에서 상계하고, 회수되지 않는 건을 수기로 이관하는 흐름)의 현행 동작을 실행 가능한 게이트에
매핑된 불변 사양으로 결정화한다.**

## 왜 이 슬라이스가 있는가

정산은 확정되면 송금된다. 그런데 **송금이 끝난 뒤에** 환불·차지백·PG 대사 조정이 도착하면
차감할 대상이 이미 셀러 계좌에 가 있다. 그 순간 그 돈은 정산 조정이 아니라 **채권**이 된다 —
셀러에게 받아야 할 돈이고, 받는 방법은 다음 정산에서 빼거나 사람이 회수하는 것뿐이다.

## 범위

| 포함 | 제외 |
|------|------|
| 채권 상태머신 3상태와 단방향 이관 | 조정 자체의 발생 규칙(환불·차지백·PG 대사 각 슬라이스 소관) |
| 발생 판정 4단계(멱등 → 송금완료 → 홀드백 흡수 → 잔여 채권화) | 홀드백 소진 규칙(정산 애그리거트 소유) |
| 상계 알고리즘(오래된 순, 잔액 상한, 초과분 이월) | 지급(payout) 상태머신(→ 회계 코어 Seed) |
| 정체 채권 이관 배치 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

> 경로 접두: `settlement-service/src/main/java/github/lms/lemuel/recovery/`

1. **상태머신 3상태, 이관은 단방향**이다 (`domain/RecoveryStatus.java:6-11,18-24`):
   ```
   OPEN ─────────────→ CLOSED           (전액 상계 도달)
     └→ MANUAL_REQUIRED → CLOSED        (수기 회수 완료)
   ```
   `MANUAL_REQUIRED → OPEN` 복귀가 **없다** — 자동 상계로 되돌리려면 **채권을 새로 발생시킨다**.
   `CLOSED` 는 종결이라 어떤 전이도 불가하다.

2. **팩토리 전용·setter 없음** — `open`(발생)·`rehydrate`(영속 복원) 둘뿐이다
   (`domain/SellerRecovery.java:17`). 채권 원금은 양수여야 한다(`:40`).

3. **발생은 4단계 순서를 지킨다** (`application/service/RecoverPostPayoutAdjustmentService.java:22-27`):
   1. **조정 1건 = 채권 1건 멱등**
   2. **즉시지급 Payout 이 `COMPLETED`(송금 완료)** 가 아니면 대상이 아니다 — 아직 안 나간 돈은
      채권이 아니라 그냥 조정이다
   3. **미해제 홀드백에서 우선 흡수** — 유보해 둔 돈이 있으면 거기서 먼저 뺀다
   4. **잔여만 채권으로 열고** 발생 분개 1건(`Dr AR / Cr AP`)

4. **정산 애그리거트를 직접 열지 않는다.** 홀드백 소진은 **정산의 규칙**이므로
   `AbsorbSettlementHoldbackPort` 로 "얼마를 흡수해 달라"만 요청하고 결과만 받는다
   (`RecoverPostPayoutAdjustmentService.java:29-30`). 슬라이스 경계를 포트로 유지한다.

5. **셀러 해석에 실패하면 아무것도 바꾸지 않고 끝낸다** — 조정 레코드 자체가 수기 대응 근거로 남는다
   (`RecoverPostPayoutAdjustmentService.java:25-27`). 절반만 처리된 상태를 만들지 않는다.

6. **상계는 잔액 상한이고 초과분은 호출자가 다음 채권으로 넘긴다** (`domain/SellerRecovery.java:71-92`):
   `consumed = min(outstanding, requested)`. 잔액이 0이 되는 순간 스스로 `CLOSED` 로 전이하고
   `closedAt` 을 찍는다.

7. **상계 순회는 오래된 순 비관락 스캔**이다 (`application/service/OffsetSellerRecoveryService.java:21-22`).
   **잔액 검증과 `CLOSED` 전이는 도메인이 소유하고**, 서비스는 순회·이력·분개만 오케스트레이션한다.

8. **상계는 후속 정산 확정 트랜잭션에 합류한다** — 별도 배치가 아니라 확정 청크 안에서 실행된다
   (`OffsetSellerRecoveryService.java:19`). 확정과 상계가 갈라지는 창이 없다.

9. **재실행 멱등은 `(recovery, settlement)` UNIQUE + 기존 상계 총액 재사용**이다
   (`OffsetSellerRecoveryService.java:23`).

10. **정체 채권은 배치가 이관한다** — `OPEN` 채권 중 마지막 활동이 유예 기간을 넘긴 건을
    `MANUAL_REQUIRED` 로 옮긴다. 홀드백 해제 배치와 동일하게 **한 번에 100건씩 페이지 처리**해
    락 경합을 줄인다 (`application/service/EscalateStaleSellerRecoveryService.java:17-20`).

11. **예외는 타입 계열이다** — `RecoveryInvariantViolationException`(불변식 위반) ·
    `InvalidRecoveryStateException`(상태 위반). generic IAE 를 쓰지 않는다.

## 진입 표면 (도달성 확인 완료)

| 유스케이스 | 부르는 어댑터 |
|---|---|
| `EscalateStaleRecoveryUseCase` | `settlement/adapter/in/batch/RecoveryEscalationScheduler` (매일 새벽) |
| `OffsetSellerRecoveryUseCase` | `settlement/application/service/ApplyLoanDeductionService` (확정 경로에서 전이적 호출) |
| `RecordPostPayoutRecoveryUseCase` | `chargeback/…/ChargebackService` · `settlement/…/AdjustSettlementForRefundService` · `settlement/…/ApplyReconciliationAdjustmentService` |

조회·수기 종결은 `/admin/recoveries` (`adapter/in/web/RecoveryAdminController.java:24`).
세 유스케이스 모두 **전이적으로 어댑터에서 도달 가능**하며 `InboundPortReachabilityTest` 가 이를 강제한다.

## 이벤트 계약

**발행 2토픽** — `lemuel.seller_recovery.opened` · `lemuel.seller_recovery.offset`.
account GL 이 각각 `recoveryOpened` · `recoveryOffset` 분개로 받는다(→ GL 코어 Seed 매핑 표).
**소비 0.**

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|---|---|---|
| AC-1 | `MANUAL_REQUIRED → OPEN` 복귀가 불가능하다 | `SellerRecoveryTest` |
| AC-2 | 상계가 잔액을 넘지 않고 초과 요청은 잔액만 소비한다 | `SellerRecoveryTest` |
| AC-3 | 잔액 0 도달 시 스스로 `CLOSED` 로 전이한다 | `SellerRecoveryTest` |
| AC-4 | 송금 완료가 아닌 조정은 채권이 되지 않는다 | `RecoverPostPayoutAdjustmentServiceTest` |
| AC-5 | 홀드백 흡수가 채권 발생보다 먼저 일어난다 | `RecoverPostPayoutAdjustmentServiceTest` |
| AC-6 | 같은 조정이 채권을 두 번 만들지 않는다 | `RecoverPostPayoutAdjustmentServiceTest` · `PostPayoutRecoveryIntegrationIT` |
| AC-7 | 같은 정산의 상계 재실행이 총액을 늘리지 않는다 | `OffsetSellerRecoveryServiceTest` + `(recovery, settlement)` UNIQUE |
| AC-8 | 정체 채권이 유예 기간 후 이관된다 | `EscalateStaleSellerRecoveryServiceTest` · `RecoveryEscalationIntegrationIT` |
| AC-9 | 모든 인바운드 포트가 어댑터에서 도달 가능하다 | `InboundPortReachabilityTest` (ArchUnit) |
| AC-10 | 발행 2토픽이 계약 스키마와 일치한다 | `SellerRecoveryEventContractTest` |
| AC-11 | 커버리지 LINE >= 90% | `./gradlew :settlement-service:jacocoTestCoverageVerification` |

**테스트 자산**: 8개 파일 = 단위 6(`SellerRecoveryTest`·`RecoveryAllocationTest`·서비스 3·계약 1) +
Testcontainers 통합 2(`PostPayoutRecoveryIntegrationIT`·`RecoveryEscalationIntegrationIT`).

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1 회수율에 상한이 없다.** 채권은 후속 정산이 있어야 상계된다 — **거래를 멈춘 셀러의 채권은
  영원히 `OPEN` 이다가 유예 기간이 지나 `MANUAL_REQUIRED` 로만 간다.** 수기 회수의 실제 성공 여부는
  이 슬라이스가 알지 못하며, `CLOSED` 는 "사람이 다 받았다고 눌렀다"는 뜻일 뿐이다.
  → `recorded-not-fixed` (경계 — 실제 회수는 시스템 밖)

- **KI-2 `MANUAL_REQUIRED` 이후의 알림·독촉 경로가 없다.** 이관은 상태 변경으로 끝나고 이벤트도
  알림도 없다 — 누군가 `/admin/recoveries` 를 열어봐야 안다.
  → `recorded-not-fixed` (notification 연계 후보)

- **KI-3 상계 순서가 "오래된 순"으로 고정돼 있다.** 금액이 큰 채권을 먼저 회수하는 정책도, 특정
  채권을 우선 지정하는 경로도 없다. 오래된 순은 공정하지만 **회수 총액을 최대화하는 순서는 아니다**.
  → `by-design` (단순성 선택 — 정책이 필요해지면 도메인에 명시할 지점)

- **KI-4 채권 상계가 확정 트랜잭션에 합류한다(불변식 8)** — 일관성을 얻는 대신, 채권 스캔이 느려지면
  **정산 확정 자체가 느려진다.** 페이지 처리는 이관 배치에만 있고 상계 경로에는 없다.
  → `recorded-not-fixed` (성능 경계)
