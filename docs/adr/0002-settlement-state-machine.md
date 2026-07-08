# ADR 0002 — 정산 상태 머신 (명시적 전이 + 멱등)

- 상태: Accepted
- 일자: 2026-01-12

## 컨텍스트

정산 1건은 결제 캡처 이벤트 수신 → 금액 계산 → 처리 → 확정/실패의 생명주기를 가진다.
초기 구현은 정산 상태(`status`)를 외부(서비스/배치/컨슈머)에서 자유롭게 `setStatus(...)` 로
바꿀 수 있었다. 이는 두 가지 문제를 낳았다.

- **불법 전이**: 이미 확정(DONE)된 정산을 다시 `PROCESSING` 으로 돌리거나, `REQUESTED` 에서
  곧장 `DONE` 으로 건너뛰는 경로가 코드상 가능했다. 금융 도메인에서 확정 정산의 재변경은
  셀러 지급액 오류로 직결된다.
- **재처리 비결정성**: 이벤트 파이프라인(Outbox + Kafka)은 at-least-once 라 같은 정산
  처리가 중복 호출될 수 있는데, 상태 전이가 방어되지 않으면 같은 정산이 두 번 확정되거나
  실패 사유가 덮어써진다.

레거시 상태값(`PENDING`, `CONFIRMED`)이 DB 에 섞여 있던 이력(V26 정리)도 있어, 상태 집합과
전이 규칙을 도메인 한곳에 못 박을 필요가 있었다.

## 결정

정산 상태 전이를 `Settlement` Aggregate Root 와 `SettlementStatus` enum 에 캡슐화한다.
외부는 `setStatus` 대신 **의도를 드러내는 전이 메서드**만 호출한다.

### 1. 상태 집합 (`SettlementStatus`)

```
REQUESTED → PROCESSING → DONE
                       ↘ FAILED → REQUESTED (재시도)
REQUESTED → CANCELED   (환불로 net ≤ 0)
```

`DONE`, `CANCELED` 는 종료 상태. `FAILED` 만 `REQUESTED` 로 되돌아가 재시도할 수 있다.

### 2. 명시적 전이 메서드 (`Settlement`)

- `startProcessing()` : REQUESTED → PROCESSING. 그 외 상태면 `IllegalStateException`.
- `complete()` : PROCESSING → DONE. `confirmedAt` 기록.
- `fail(reason)` : PROCESSING → FAILED. 실패 사유 보존.
- `retry()` : FAILED → REQUESTED. 실패 사유 초기화.
- `cancel()` : DONE 이면 거부(`"DONE settlements cannot be canceled"`), 그 외 → CANCELED.

각 메서드는 **진입 상태를 선검증**하고 위반 시 예외를 던져 불법 전이를 원천 차단한다.
전이 가능성 판정은 `SettlementStatus.canTransitionTo(target)` 에 별도로 명문화해 테스트와
방어 코드가 같은 규칙을 공유한다.

### 3. 멱등 / 종료 상태 불변

- `complete()` 는 PROCESSING 에서만 동작하므로, 이미 DONE 인 정산에 재호출되면 예외 →
  중복 확정 불가.
- `adjustForRefund(...)` 는 DONE 정산을 직접 수정하지 않고 예외를 던져, 확정 정산의 금액
  불변을 보장한다(환불은 별도 레코드로 — [0004](0004-reverse-settlement-via-adjustment.md)).
- 낙관적 락(`version`, JPA `@Version`)으로 동시 전이를 충돌 감지한다.

### 4. 레거시 호환

`confirm()` 은 REQUESTED → PROCESSING → DONE 를 한 번에 수행하는 편의 메서드로 남기되,
신규 코드는 `startProcessing()`/`complete()` 를 직접 호출하도록 한다. 알 수 없는 상태 문자열은
`SettlementStatus.fromString(...)` 이 `REQUESTED` 로 fallback 해 롤백 이력에 방어한다.

## 결과

### 좋아지는 점
- 불법 전이가 컴파일/런타임 경계에서 차단 — 확정 정산 재변경 불가
- at-least-once 재처리에 안전(종료 상태 재진입 시 예외)
- 상태 규칙이 도메인 한곳(`Settlement` + `SettlementStatus`)에 집중 → 테스트·감사 용이

### 트레이드오프 / 리스크
- 호출부가 `setStatus` 대신 전이 메서드를 써야 함(학습 비용, 레거시 호출부 마이그레이션)
- 예외 기반 방어라 호출부가 진입 상태를 알고 호출하지 않으면 런타임 예외 발생
- `confirm()` 같은 단축 메서드는 상태 머신을 우회하는 듯 보여 오용 여지 → 신규 코드 사용 금지로 완화

## 대안 검토

| 옵션 | 채택? | 이유 |
|---|---|---|
| 자유 `setStatus` (현행 이전) | ✗ | 불법 전이·중복 확정 차단 불가 |
| **enum 전이 규칙 + Aggregate 전이 메서드 (본 결정)** | ✓ | 규칙 단일화, 종료 상태 불변, 멱등 보장 |
| 상태 패턴(State 객체 per 상태) | ✗ | 상태 5개·전이 단순 — 클래스 폭증 대비 이득 미미 |
| 외부 워크플로 엔진 | ✗ | 단일 Aggregate 생명주기에 과한 인프라 |

## 참조

- [0003 — Transactional Outbox 패턴](0003-transactional-outbox-pattern.md)
- [0004 — 환불 시 역정산 (SettlementAdjustment)](0004-reverse-settlement-via-adjustment.md)
- [0014 — 셀러 등급별 T+N 정산 주기](0014-tier-based-settlement-cycle.md)
