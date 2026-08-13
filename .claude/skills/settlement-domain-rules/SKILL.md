---
name: settlement-domain-rules
description: 정산 도메인 핵심 규칙 — 상태머신, 등급별 수수료/주기/홀드백, 역정산(조정) 원칙. 정산 생성/확정/조정 로직을 작성·수정·리뷰할 때 로드.
---

# 정산 도메인 규칙

## 상태머신 (위반 코드 즉시 지적)

```
Settlement: REQUESTED → PROCESSING → DONE | FAILED | CANCELED
Payout:     REQUESTED → SENDING → COMPLETED | FAILED | CANCELED
Chargeback: OPEN → ACCEPTED | REJECTED
Ledger:     PENDING → POSTED → REVERSED
Payment:    READY → AUTHORIZED → CAPTURED → REFUNDED  (AUTHORIZED→FAILED, CAPTURED→CANCELED)
```

- 전이는 도메인 모델의 전이 메서드로만 한다 (예: `OrderStatus.canTransitionTo()` + `Order.transitionTo()`).
  setter 로 status 를 직접 바꾸는 코드는 반려하라.
- DONE/COMPLETED/POSTED 이후의 "수정"은 없다 — 아래 역정산 원칙으로만 정정한다.

## 등급별 정책 (근거: `settlement.domain.SellerTier`, `HoldbackPolicy`)

| 등급 | 수수료율 | 정산 주기 | 홀드백 |
|---|---|---|---|
| NORMAL | 3.5% (`0.0350`) | T+7 영업일 | 30%, 30일 후 해제 |
| VIP | 2.5% (`0.0250`) | T+3 영업일 | 10%, 14일 후 해제 |
| STRATEGIC | 2.0% (`0.0200`) | T+1 영업일 | 0% (즉시 전액) |

- 계산 순서: `수수료 차감 → net 산출 → net 에 홀드백율 적용 → 즉시지급분/보류분 분리`.
- 정산 주기는 `users.settlement_cycle` 명시값이 있으면 그것이 우선, 없으면 `SellerTier.defaultCycle`.
- 홀드백 해제일은 **영업일 기준** (`HoldbackPolicy.computeReleaseDate` → `BusinessDayCalculator`).
- 레거시 상수 `Settlement.COMMISSION_RATE`(3%) 는 보존용일 뿐 — 신규 코드가 참조하면 지적하라.

## 수수료율 스냅샷 (V32 `commission_rate`)

- 정산 생성 시점의 요율을 `settlements.commission_rate` 에 **영구 저장**한다.
- 이후 등급/요율이 바뀌어도 과거 정산은 재계산하지 않는다.
- "요율 테이블만 조인해서 계산하자"는 설계는 이력 훼손 — 반드시 스냅샷 컬럼을 쓰게 하라.
- **유효기간 요율 정책(ADR 0032)**: 적용 요율은 `CommissionRatePolicy.resolve()` 가 SELLER > TIER >
  등급 기본율 순으로 정한다(`[from, to)` 반개구간, 기간 중첩은 DB `EXCLUDE USING gist` 가 차단).
  결정 근거는 `settlements.commission_rate_source`(예: `SELLER:77`·`TIER:VIP`·`DEFAULT_TIER`)에 남는다.
  정책은 **미래에만** 건다 — 소급 구간에 이미 정산이 있으면 등록이 거부되고 역정산(`settlement_adjustments`)이
  정식 경로다.

## 역정산 = 조정 트랜잭션 (ADR 0004)

- 환불/취소가 발생하면 기존 정산 row 를 고치지 않고 `settlement_adjustments`(음수 금액) 를 **추가**한다.
- 조정에도 당시 스냅샷 수수료율을 적용한다 (환불 수수료 반환 계산).
- 원장에는 역분개(REVERSED) 로 반영한다 — ledger-invariants skill 참조.

## 지급액 차감 순서 (T-4 2026-08-12 · L-3 2026-08-13 확정)

정산 확정 시 즉시지급분에서 빼는 순서는 **고정**이다 — 바꾸면 세무·회수 리스크가 생긴다.

```
payout = immediate − 원천징수 − 대출 상환차감 − 채권상계   (immediate = net − 미해제 holdback)
```

**순서를 정하는 기준은 "못 뗐을 때 이월되는가"** 하나다. 이월되는 쪽을 뒤로 미룬다.

| 순위 | 차감 | 못 뗐을 때 | 근거 |
| --- | --- | --- | --- |
| 1 | 원천징수 | **소실**(과소징수·가산세) | 이월 장치 없음. 국세 |
| 2 | 대출 상환차감 | **손실** | loan 이 이미 대출 잔액을 줄이고 상환을 기록한다 — 이번 회차에 못 떼면 채권만 사라진다 |
| 3 | 채권상계 | 지연 | `SellerRecovery` 가 OPEN 으로 남아 다음 정산에서 회수된다 |

> **트리거 주의(L-3, 2026-08-13 배선 완료)**: 이 차감은 **확정 배치가 아니라 상환차감 수신 시점**에
> 일어난다(`ApplyLoanDeductionService`). 확정 시점에 금액을 확정하면 뒤늦게 도착하는 대출 차감을
> 반영할 수 없어(`Payout.amount` 는 final) 대출 잔액만 줄고 현금은 전액 나가던 결함이 있었다.
> 그래서 **확정 배치는 지급을 만들지 않는다** — `RequestPayoutUseCase`·`OffsetSellerRecoveryUseCase`
> 의존 자체를 제거해 구조로 막았다. 확정 시점에 남는 것은 원천징수(1순위)뿐이고, 지급 단계가 같은
> 입력으로 재계산해 동일 값을 쓴다(통제계정이 0 으로 닫힌다).

- **원천징수가 항상 먼저다.** 못 뗀 채권은 `OPEN` 으로 남아 다음 정산에서 상계되지만(이월 경로 존재),
  못 뗀 원천징수는 이월 장치가 없어 소실 = 과소징수(가산세)다. 회수가 지연될 뿐인 쪽을 뒤로 미룬다.
- 상계 서비스에는 **원천징수를 뗀 잔여액**을 가용액으로 넘긴다 — 세금 재원을 채권 회수가 잠식하지 못한다.
- 현행 등급 정책(최대 홀드백 30%)에서는 `immediate ≥ 0.7×net > 0.033×net` 이라 원천징수는 **항상 전액 확보**된다.
  `min()` 클램프는 홀드백이 96.7% 를 넘는 미래에 대한 방어선이며, 걸리면 `settlement.withholding.shortfall`
  메트릭이 오른다(운영 알람 대상 — 정상 상태는 0).
- 상계 재실행은 기존 상계 총액을 그대로 반환하므로 지급액에 `max(0)` 하한을 둔다(음수 지급 요청 금지).

## 검증 도구

- 계산 결과 기대값 검증: MCP `settlement_simulate` (amount, tier → fee/holdback/immediatePayout).
- **적용 요율 교차검증**: `GET /admin/commission-rates/simulate?sellerId=&tier=&at=` — 특정 시점에 어떤
  정책이 이길지와 그 근거(`source`)를 확정 없이 확인한다. 요율 이견이 나오면 여기서 먼저 대조하라:
  `settlements.commission_rate_source` 와 simulate 의 `source` 가 다르면 정산 이후 정책이 바뀐 것이며,
  그 경우 **과거 정산이 맞다**(스냅샷 보존).
- 구현 후에는 등급 3종 × (정상/환불/경계금액 0원·1원) 매트릭스 테스트를 제안하라.
