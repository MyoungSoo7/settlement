# ADR 0015 — 정산 홀드백(Holdback) 정책

- 상태: Accepted
- 일자: 2026-03-04

## 컨텍스트

[0014](0014-tier-based-settlement-cycle.md) 로 등급별 T+N 정산 주기를 도입했지만, 정산일에
**전액을 즉시 지급**하면 그 이후 들어오는 환불·분쟁 리스크를 셀러에게 회수하기 어렵다. 특히 신뢰도가
낮은(신규/환불 다발) 셀러는 지급 후 환불이 몰리면 음수 잔액이 발생하고, 회수 실패 시 플랫폼이 손실을
떠안는다.

확정 후 환불은 [0004](0004-reverse-settlement-via-adjustment.md) 의 역정산 레코드로 *기록*되지만,
기록만으로는 이미 지급된 돈을 되돌릴 수 없다. 지급 자체를 일부 **유보**할 안전장치가 필요하다.

## 결정

정산금 일부를 등급별 정책에 따라 일정 기간 **보류(holdback)** 했다가, 환불·분쟁이 없으면
`holdbackReleaseDate` 에 자동 해제하여 지급한다.

### 1. 등급별 보류 정책 (`HoldbackPolicy.forTier`)

| 등급 | 보류율 | 보류 기간 |
|---|---|---|
| NORMAL | 30% (`0.30`) | 30일 |
| VIP | 10% (`0.10`) | 14일 |
| STRATEGIC | 0% | 0일 (즉시 전액 정산) |

`HoldbackPolicy` 는 `record(BigDecimal rate, int releaseDays)` 로 rate 0~1, releaseDays ≥ 0 을
생성자 불변식으로 강제한다. 실 운영은 셀러별 분쟁률·환불률 기반 동적 조정(FDS 연계)으로 확장.

### 2. 보류 적용·해제 (`Settlement`)

- `applyHoldback(rate, releaseDate)` : `holdbackAmount = netAmount × rate` 계산, `holdbackRate`/
  `holdbackReleaseDate` 스냅샷 보존. rate 0% 면 즉시 released 처리.
- `holdbackReleaseDate` 는 `HoldbackPolicy.computeReleaseDate(settlementDate)` 가 산출 —
  `BusinessDayCalculator.addBusinessDays(...)` 로 영업일 N일 후([0014](0014-tier-based-settlement-cycle.md) 와 동일 계산기).
- `releaseHoldback(today)` : release_date 도달 후 배치가 호출. 이미 해제됐거나 release 시점 전이면
  무시/예외. `isHoldbackReleasable(today)` 로 배치 스캔.
- `getImmediatePayoutAmount()` : 즉시 지급 가능액 = `netAmount − holdbackAmount`(미해제 시).

### 3. 환불을 holdback 에서 우선 차감

`consumeHoldbackForRefund(refundAmount)` 가 환불을 보류분에서 먼저 차감한다. 보류 잔액으로 충분하면
셀러 net 에 영향이 없어, 지급 *후* 회수 불가 문제를 구조적으로 회피한다. 보류가 소진되면 자동 released.

### 4. 보류율 스냅샷 보존

`holdbackRate` 를 정산 시점에 보존해, 정책이 바뀌어도 과거 정산의 보류 계산은 변하지 않는다
([0014](0014-tier-based-settlement-cycle.md) 의 요율 스냅샷 원칙과 동일).

## 결과

### 좋아지는 점
- 환불·분쟁 리스크를 보류분에서 흡수 — 지급 후 회수 실패 손실 방지
- 등급별 차등으로 신뢰 셀러는 보류 0%(STRATEGIC), 고위험 셀러는 30% 버퍼
- 영업일 기준 자동 해제로 운영 개입 최소화

### 트레이드오프 / 리스크
- 셀러 입장에서 현금 회수 지연(NORMAL 30%/30일) — 등급 상향 인센티브로 완화
- 보류·해제·환불차감 상태(`holdbackReleased`/`holdbackAmount`)가 정산 레코드에 추가돼 복잡도 증가
- 해제 배치 누락 시 지급 지연 → `isHoldbackReleasable` 스캔 모니터링 필요

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| 보류 없이 전액 즉시 지급 | ✗ | 지급 후 환불 회수 불가, 플랫폼 손실 |
| 전 셀러 단일 보류율 | ✗ | 신뢰 셀러에 불필요한 현금 지연 |
| 외부 에스크로 위탁 | ✗ | 수수료·정산 지연 증가, 내부 원장과 단절 |
| **등급별 holdback + 영업일 자동해제 (본 결정)** | ✓ | 리스크 흡수 + 차등 + 운영 자동화 |

## 참조

- [0004 — 환불 시 역정산 (SettlementAdjustment)](0004-reverse-settlement-via-adjustment.md)
- [0014 — 셀러 등급별 T+N 정산 주기](0014-tier-based-settlement-cycle.md)
