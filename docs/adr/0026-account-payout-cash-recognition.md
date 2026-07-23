# ADR 0026 — 계정계 payout 현금흐름 인식 + 시산표 실검증 — 채택(Option A)

- 상태: Accepted (Option A) — 2026-07-23 확정, 구현 진행 (full A1)
- 일자: 2026-07-12 (제안) / 2026-07-23 (채택)
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

## 결정 (2026-07-23 — Accepted)

**Option A 채택** (정산 중심 최소 모델). 결정 포인트 확정:

1. **SELLER_PAYABLE 상계 시점**: 지급(payout) 시. (권장안 채택)
2. **현금 유입 모델링 범위**: Option A — 정산 생성 시 CASH 인식. Option B(결제계 소비·수수료 수익)는 후속 ADR 로 분리.
3. **신규 계정**: 불필요 (CASH·SELLER_PAYABLE 기존 계정만).

부수 정책(구현 범위 5 관련):

- **`settlement.confirmed` 처리**: 컨슈머·토픽 **유지**(loan 상환 saga 가 여전히 소비), 단 GL **무전표**(멱등 마커만). 토픽 삭제 금지.
- **과거 GL 백필**: 신규 전기 규칙은 **cut-over 이후 이벤트에만** 적용. 과거분은 전면 재처리하지 않고, 잔존 `SETTLEMENT_SCHEDULED` 잔액을 **마감 조정분개로 청산**. 전면 재처리는 필요 시 별도 백필 배치.
- **마감 후 조정**(settlement 자체원장 월마감 관련): 마감된(CLOSED) 기간은 재개봉 금지 — CLOSED 기간 대상 역분개는 차단하지 않고 **다음 OPEN 기간으로 `adjustmentDate` 재지정**해 전기(회계 관행).
- **`payout.completed` 직렬화**: settlement 계열 규약대로 `amount` 는 JSON number, 계약 스키마 `"type":"number"`.

> 되돌리기 어려운 회계 결정이므로, 구현은 매핑 변경·신규 이벤트·백필을 **한 릴리스로 원자 배선**한다(부분 배선 시 CASH 반쪽). Phase 0–3 은 함께 랜딩.

## 정정 (2026-07-23 — Option ① 정밀 모델)

독립 GL 감사에서 Option A v1(단일 `SELLER_PAYABLE = net`)이 **통제계정을 0으로 닫지 못함**을 발견: CR SELLER_PAYABLE(created)=net 전액 vs DR SELLER_PAYABLE(payout)=실지급액(net−holdback−회수상계−환불소진). 차액(회수상계 O·소진 holdback C)이 GL 이벤트 없이 영구 잔존해 허위 미지급금·현금을 계상(차대균형·정상방향 가드 모두 미탐). → **Option ① 채택**: 지급액 기준 인식 + 유보 별도 부채계정 + 감액 사건 GL mirror.

**Chart of Accounts 추가**: `HOLDBACK_PAYABLE`(대변성), `SELLER_RECOVERY_RECEIVABLE`(차변성).

**사건×전표(차1·대1)**: created→DR CASH/CR SELLER_PAYABLE(즉시분 I=net−holdback) + DR CASH/CR HOLDBACK_PAYABLE(H) · payout완료→DR SELLER_PAYABLE/CR CASH(실지급) · 회수발생→DR RECEIVABLE/CR CASH(R) · 회수상계→DR SELLER_PAYABLE/CR RECEIVABLE(O) · 유보해제→DR HOLDBACK_PAYABLE/CR SELLER_PAYABLE(Hr) · 유보소진→DR HOLDBACK_PAYABLE/CR CASH(Hc) · 확정전조정/취소→역분개.

**신규 이벤트**(settlement 발행, account 소비, ADR 0024 계약): `settlement.created`(holdbackAmount 추가)·`settlement.holdback_released`·`seller_recovery.opened`·`seller_recovery.offset`·`settlement.holdback_consumed`·`settlement.adjusted`·`settlement.canceled`.

**검증 강화**: `sellerFullySettled` 통제계정 0 봉합 + GL↔서브원장 `/control-recon` + 신규 계정 `normalBalanceRespected` 자동 확장.

**균형 증명**(완전정산): SELLER_PAYABLE=+I−(I−O)−O+Hr−Hr=0, HOLDBACK_PAYABLE=+H−Hc−Hr=0, RECEIVABLE=+R−ΣO=0, CASH 회수종료 시 0. → HIGH-1 제거.

**열린 질문 기본값**(회계 오너 확정, 가역): ①현금 인식은 Option A v1(생성 시 DR CASH) 유지 ②회수발생 상대계정=CASH ③확정전 환불 감액 레그는 payload `targetLeg` 분기 ④수동 payout(settlementId=null)은 별도 정책(MEDIUM, normalBalanceRespected가 방어).

> 원자 배선 최소 범위 = Phase 1{created 분리 + 팩토리 + 컨슈머 + ref_type/account CHECK 마이그레이션 + 백필}. 부분 배선 시 SELLER_PAYABLE·CASH 즉시 왜곡.

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
