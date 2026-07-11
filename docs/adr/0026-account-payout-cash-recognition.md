# ADR 0026 — 계정계 payout 현금흐름 인식 + 시산표 실검증 — 제안

- 상태: Proposed (검토용 — 아직 실행하지 않음)
- 일자: 2026-07-12
- 관련: ADR 0024(이벤트 계약), ADR 0020(이벤트 드리븐 프로젝션), account-domain-rules 스킬, ledger-invariants
- 배경: 전사 GL 감사에서 발견된 두 MED 항목(“셀러 실지급 CASH 유출 GL 미반영” + “시산표 항등식”)

## 컨텍스트

`account-service`(계정계)는 loan·investment·settlement 이 발행하는 6개 토픽을 소비해 복식부기 GL 로 집계한다(소비 전용).
현재 정산(settlement) 흐름은 **CASH 를 전혀 건드리지 않는 폐루프**다:

```
settlement.created   : DR SETTLEMENT_SCHEDULED / CR SELLER_PAYABLE
settlement.confirmed : DR SELLER_PAYABLE       / CR SETTLEMENT_SCHEDULED
→ 확정 시점에 두 계정이 모두 0 으로 자기상계된다.
```

CASH 는 loan·investment(선지급/집행 유출, 상환 유입)에만 등장한다.

### 문제 1 — 셀러 실지급(payout) 현금 유출이 GL 에 통째로 없다
플랫폼 최대 자금흐름인 **셀러 정산금 지급(payout)** 은 settlement-service 의 payout 도메인에서 일어나지만
어떤 이벤트도 발행하지 않아 account-service 가 소비할 수 없다. 결과적으로 GL·시산표에 셀러 현금 유출이 부재하다.

### 문제 2 — SELLER_PAYABLE 이 “확정” 시점에 조기 상계된다
회계상 미지급금(SELLER_PAYABLE)은 **지급(payout) 시** 상계돼야 하는데, 현재는 `settlement.confirmed` 에서 이미
0 으로 지워진다. 그래서 payout 을 그대로 `DR SELLER_PAYABLE / CR CASH` 로 얹으면 상계할 잔액이 없어
대변성 계정이 순차변(음수)이 되는 또 다른 이상이 생긴다.

### 문제 3 — 시산표 `balanced()` 는 항등식이라 실검증력이 없다
전표가 구성적으로 차=대 균형이라 `totalDebit == totalCredit` 은 **항상 참**이다. 반쪽 전표(데이터 손상) 외에는
아무것도 잡지 못한다.

### 근본 원인
정산 GL 이 **고객 결제 현금 유입(order → CASH)** 을 모델링하지 않기 때문에, payout(현금 유출)만 추가하면
CASH 가 한쪽짜리가 되어 복식부기가 닫히지 않는다. 즉 payout 인식은 “상계 시점 변경” 그 이상으로,
**계정과목(Chart of Accounts) 흐름의 재설계**가 필요하다.

## 결정 포인트 (팀/회계 오너 확정 필요)

1. **SELLER_PAYABLE 상계 시점**: 확정(현행) vs **지급(payout)** — 회계 정합상 *지급 시* 권장.
2. **현금 유입 모델링 범위**: (A) 정산 생성 시 CASH 인식(정산 중심 단순화) vs (B) order `payment.captured` 소비로
   결제 시점 CASH 인식 + 수수료 수익(신규 계정) 반영(정확·대규모).
3. **신규 계정 필요 여부**: Option B 는 `COMMISSION_REVENUE`(대변성) 등 추가 필요.

## 제안 (두 옵션)

### Option A — 정산 중심 최소 모델 (권장: 우선 도입)
정산 라이프사이클 안에서 CASH 를 닫는다. SETTLEMENT_SCHEDULED 클리어링을 CASH 로 대체.

```
settlement.created   : DR CASH          / CR SELLER_PAYABLE   (플랫폼이 현금 보유, 셀러에 지급의무)
settlement.confirmed : (GL 전표 없음 — 상태 전이만. 필요 시 memo)
payout.completed     : DR SELLER_PAYABLE / CR CASH            (셀러 지급 → 미지급금 상계 + 현금 유출)
```

- created+payout 후 CASH·SELLER_PAYABLE 모두 0 으로 닫힌다(플랫폼 pass-through). SETTLEMENT_SCHEDULED 제거/보류.
- 장점: 폐루프로 대차가 닫히고 payout 이 GL 에 반영됨. 단점: 현금을 결제 시점이 아닌 정산 시점에 인식(단순화),
  플랫폼 수수료 수익은 GL 미반영.

### Option B — 결제~지급 전체 정확 모델 (대규모)
order `payment.captured` 를 소비해 결제 시점 CASH 유입 + 수수료 수익 인식, 정산은 발생주의 이연,
payout 은 현금 유출. `COMMISSION_REVENUE` 등 계정 추가. 가장 정확하나 order 결제계까지 걸친 프로젝트.

## 구현 범위 (결정 확정 후)

1. **settlement-service (발행)** — payout 이 COMPLETED 로 전이할 때 Outbox 로 `lemuel.payout.completed` 발행
   (PayoutSingleExecutor). account 는 소비 전용이므로 발행은 반드시 settlement 몫.
2. **이벤트 계약 (ADR 0024)** — `payout.completed` JSON Schema + 정본 샘플을
   `shared-common/src/testFixtures/resources/contracts/events/` 에 추가, 프로듀서/컨슈머 양방향 계약 테스트.
3. **account-service (소비)** — `PayoutCompletedConsumer`(IdempotentEventConsumer 상속, `app.kafka.enabled` 게이트)
   + `AccountEntry.payoutCompleted(sellerId, payoutId, amount)` 팩토리(`DR SELLER_PAYABLE / CR CASH`).
   Option A 채택 시 `settlementCreated`/`settlementConfirmed` 팩토리 매핑 변경 동반.
4. **시산표 실검증 (3a)** — 위 모델로 CASH 가 코히런트해진 뒤에야 `normalBalanceRespected()`
   (계정별 순잔액 부호가 정상방향 위반 시 이상 탐지)이 의미를 가진다. `balanced()` 는 유지(방어값).
5. **마이그레이션/테스트** — settlement.created/confirmed 전기 변경은 기존 account 테스트·과거 GL 데이터 의미를
   바꾸므로, 기존 데이터 재처리(백필) 여부와 계약 버전 전략을 함께 결정한다.

## 왜 지금 코드로 안 하는가
payout 전기를 부분적으로 얹으면 CASH 가 한쪽짜리가 되어 **원장이 잘못 닫힌다**(돈 관련, 되돌리기 어려움).
`money-safety`/`ledger-invariants` 관점에서 상계 시점·현금 인식 범위(결정 포인트 1~3)는 회계 오너가 확정한 뒤
구현해야 한다. 본 ADR 은 그 결정을 위한 것이며, 확정되면 위 “구현 범위”를 안전하게 진행한다.

## 참고 — 이번에 함께 처리된 감사 항목
본 GL 재설계와 별개로, 동일 감사의 인가·시크릿·SSRF 계열 10건은 이미 수정·병합됨(develop). 본 항목만 회계
결정이 선행돼야 하는 성격이라 분리했다.
