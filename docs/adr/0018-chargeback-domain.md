# ADR 0018 — 차지백(Chargeback) 도메인

- 상태: Accepted
- 일자: 2026-04-08

## 컨텍스트

환불(Refund)과 차지백(Chargeback)은 둘 다 "돈이 고객에게 돌아간다"는 점은 같지만 흐름과 회계가
다르다:

- **Refund**: 고객이 셀러에게 환불 요청 → 셀러 응답 → 환불 처리 (운영사 통제 가능)
- **Chargeback**: 고객이 카드사에 신고 → 카드사가 PG 에 **강제 차감** → PG 가 운영사에 통지
  (운영사는 사후 수용 또는 증빙 제출만 가능)

차지백은 운영자 결정(셀러 책임 인정 vs 기각)이 필요한 명시적 분쟁이고, ACCEPTED 시 셀러 정산금에서
환수가 발생한다. 이를 환불 로직에 섞으면 "강제 차감 + 운영자 결정 + 멱등(PG 중복 통지)" 의미가
환불 흐름과 충돌한다. 따라서 **차지백을 별도 도메인 상태 머신**으로 분리한다.

## 결정

### 1. Chargeback 도메인 + 상태 머신

`chargeback` 도메인에 `Chargeback` POJO 와 `ChargebackStatus` 를 둔다:

```
OPEN → ACCEPTED   (셀러 책임 인정 — 정산금 환수)
     → REJECTED   (셀러 증빙 제출, 운영자 승인 — 분쟁 종결, 정산 영향 없음)
```

`canTransitionTo` 는 `OPEN` 에서만 결정을 허용하고 ACCEPTED/REJECTED 는 종결(`isFinal`)이다.
핵심 불변식(`Chargeback.java`):

- `amount > 0` (도메인 + DB CHECK 이중 방어)
- `accept`/`reject` 모두 `decidedBy` 필수 — 누가 결정했는지 감사
- `reject` 는 사유(note) 필수 — 운영 검토 근거
- `PG_WEBHOOK` 출처는 `pgChargebackId` 필수 — 멱등 키
- `linkSettlement` 은 종결 상태에서 금지 — 정산 생성 전 분쟁의 settlementId 백필용

출처는 `ChargebackSource` enum(`PG_WEBHOOK` / `MANUAL`)으로 자동/수동을 구분한다.

### 2. 멱등 + 회계 경계

`ChargebackService.open(...)` 은 `PG_WEBHOOK` + `pgChargebackId` 중복 통지 시 기존 record 를 반환해
멱등을 보장한다(`chargeback.idempotent.hit` 메트릭). 도메인은 *결정* 만 표현하고, ACCEPTED 의 회계
효과(정산금 환수)는 application service 가 책임진다 — `settlementId` 가 있으면
`SaveSettlementAdjustmentPort` 로 `SettlementAdjustment.ofChargeback(...)` 음수 row 를 생성한다.
이때 Chargeback 은 Settlement 의 도메인 모델을 import 하지 않고 같은 서비스 내 다른 컨텍스트의
저장 포트만 호출한다(헥사고날 경계 유지). 이미 Payout COMPLETED 된 정산의 환수(ReversePayout)는 본
도메인 범위 밖이다.

운영자 API 는 `ChargebackAdminController`(`/admin/chargebacks`, ROLE_ADMIN)로 노출한다.

## 결과

### 좋아지는 점

- 강제 차감·운영자 결정·멱등 의미가 환불 흐름과 분리되어 각자 단순해짐
- ACCEPTED 가 `settlement_adjustments` 음수 row 로 회계에 추적 가능하게 반영
- PG 중복 통지를 `pgChargebackId` 로 멱등 처리 — 이중 환수 방지
- 모든 결정에 `decidedBy` 강제 — 분쟁 감사 추적 확보

### 트레이드오프 / 리스크

- COMPLETED Payout 의 환수(ReversePayout)는 미구현 — 별도 단계로 분리
- 정산 생성 전 분쟁은 settlementId 백필 타이밍 의존(정산 생성 시 조회 후 연결 — Phase 3)
- 재오픈 미지원 — 동일 PG 분쟁 재발생은 새 row 로만 표현

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| **Refund 도메인에 차지백 포함** | ✗ | 강제차감/운영자결정/멱등 의미가 환불과 충돌 |
| **상태 없는 단순 차감 기록** | ✗ | 운영자 결정·기각 워크플로·감사 추적 불가 |
| **별도 Chargeback 상태 머신 (본 결정)** | ✓ | 분쟁 라이프사이클 명시 + 멱등 + 회계 경계 유지 |

## 참조

- [0004 — Reverse Settlement via Adjustment](0004-reverse-settlement-via-adjustment.md)
- [0016 — 지급(Payout) 도메인 + 펌뱅킹](0016-payout-domain-firm-banking.md)
