# ADR 0004 — 환불 시 역정산 (SettlementAdjustment)

- 상태: Accepted
- 일자: 2026-01-19

## 컨텍스트

정산이 확정(DONE)된 뒤에도 환불·카드사 분쟁(chargeback)은 발생한다. DONE 정산은 이미 셀러에게
지급(payout)이 진행/완료된 상태라, 환불을 원 정산 레코드의 금액에 직접 반영(`netAmount` 차감)하면
다음 불변식이 깨진다.

- **확정 정산 불변**: 지급 완료된 정산의 금액을 사후 변경하면 [0002](0002-settlement-state-machine.md)
  의 종료 상태 불변과 충돌하고, 이미 발생한 지급과 정산 레코드가 불일치한다.
- **감사 추적**: 환불이 원 레코드를 덮어쓰면 "얼마가 왜 차감됐는지" 이력이 사라진다. 금융 데이터는
  사후 정정이 아니라 정정 *기록*이 누적돼야 한다(append-only).
- **원장 정합성**: 복식부기 원장([0007](0007-daily-reconciliation-and-ledger-invariants.md))은 모든 금액 이동을 분개로
  남기므로, 환불도 독립된 차감 분개의 근거가 될 별도 레코드를 가져야 한다.

`Settlement.adjustForRefund(...)` 는 아직 미확정(REQUESTED/PROCESSING) 정산에 대해서만 net 을
재계산하고, DONE 정산에는 예외를 던진다(`"DONE settlement is immutable. Use SettlementAdjustment ..."`).
즉 확정 후 환불을 표현할 별도 메커니즘이 필요하다.

## 결정

확정 정산을 수정하지 않고, 환불·분쟁을 **별도의 음수 금액 레코드** `SettlementAdjustment` 로
append-only 기록한다.

### 1. 역정산 레코드 (`SettlementAdjustment`)

- 원 정산을 가리키는 `settlementId` + 음수 `amount` 를 보유. 금액은 항상 `negate()` 된 음수로
  적재해 "차감분"임을 부호로 명시한다(감사 규약).
- 팩토리:
  - `ofRefund(settlementId, refundAmount, adjustmentDate)` — 환불 반영. `refundId` 연결(엔티티 도입 전 nullable).
  - `ofChargeback(settlementId, chargebackId, chargebackAmount, adjustmentDate)` — 카드사 분쟁 ACCEPTED 차감.
- `refundId` 와 `chargebackId` 는 **상호 배타**(둘 중 하나만) — V44 `chk_adjustment_refund_xor_chargeback`
  제약과 일치. `ofChargeback` 은 `chargebackId` 만 채우고 `refundId` 는 NULL.

### 2. append-only 원칙

원 `Settlement` 레코드는 DONE 이후 **불변**. 환불·분쟁은 새로운 adjustment row 로만 누적되며,
셀러 실지급 정정은 (원 정산 net) + Σ(연결된 adjustments, 음수) 로 *유도*한다. 기존 데이터 삭제·수정 없음.

### 3. 미확정 정산은 인라인 반영 유지

DONE 이전(REQUESTED/PROCESSING) 정산은 아직 지급 전이므로 `adjustForRefund(...)` 가 net 을
직접 재계산하고, 환불로 net ≤ 0 이 되면 `CANCELED` 로 전이한다([0002](0002-settlement-state-machine.md)).
즉 역정산 레코드는 *확정 후* 환불을 위한 장치다.

### 4. 대사 가능성

리포트의 불변식 #2 `|Σ(adjustments)| = Σ(linked refunds)` 로 조정-환불 원장 정합성을 주기적으로
검증한다([0008](0008-cashflow-report-domain.md)).

## 결과

### 좋아지는 점
- 확정 정산 불변 보존 — 지급 완료 레코드와 정산 데이터 불일치 제거
- 환불·분쟁 이력이 음수 레코드로 영구 보존(감사·분쟁 대응)
- 원장 분개·대사가 adjustment 레코드를 1급 근거로 사용 가능

### 트레이드오프 / 리스크
- 셀러 실지급액이 단일 컬럼이 아니라 `정산 net + Σ(adjustments)` 유도값 → 조회 시 합산 필요
- refund/chargeback XOR 제약을 코드·DB 양쪽에서 유지해야 함
- adjustment 상태(`PENDING` 등) 생명주기 관리 부담 추가

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| 원 정산 `netAmount` 직접 차감 | ✗ | 확정 정산 불변·감사 추적 파괴 |
| 정산 레코드 버전 복제(soft-update) | ✗ | 원장·지급 연결 복잡, 이력 추적 모호 |
| **음수 SettlementAdjustment append-only (본 결정)** | ✓ | 불변 보존 + 이력 누적 + 원장 분개 근거 |

## 참조

- [0002 — 정산 상태 머신](0002-settlement-state-machine.md)
- [0007 — 복식부기 원장](0007-daily-reconciliation-and-ledger-invariants.md)
- [0008 — 캐시플로우 리포트 도메인](0008-cashflow-report-domain.md)
