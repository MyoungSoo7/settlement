# Seed — settlement-service 회계 코어 루프 as-is 사양

> **상태: CONFIRMED** (2026-07-18) · 정본 데이터: [`settlement-service-accounting-core.seed.yaml`](./settlement-service-accounting-core.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화. 자매 Seed: [order-service 핵심 커머스 루프](./order-service-core-commerce.seed.md).

## Goal (한 줄)

**settlement-service 회계 코어 루프(정산 생성·수수료/홀드백 계산·출금·복식부기 원장 + 소비/발행 이벤트 계약과
프로젝션 뷰)의 현행 동작을 실행 가능한 게이트에 매핑된 불변 사양으로 결정화해,
회귀 기준선 · 계약 드리프트 게이트 · 면접/포트폴리오 문서 · 후속 기능 베이스로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| settlement (생성·상태머신·수수료 스냅샷·홀드백·환불 조정) | chargeback · pgreconciliation · report · recon 내부 |
| payout (출금 상태머신) | integrity 스위트, search 인덱싱 내부 |
| ledger (복식부기 전표) | |
| 소비 6토픽 + 발행 2토픽 계약 표면 | |
| 프로젝션 뷰 4종 (ADR 0020) | |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **상태머신 4종** — 전이표는 각 enum 이 단일 출처 (`canTransitionTo`):
   - Settlement: `REQUESTED→PROCESSING→DONE|FAILED|CANCELED`, `FAILED→REQUESTED`(재시도) (`SettlementStatus.java:33-48`)
   - Payout: `REQUESTED→SENDING→COMPLETED|FAILED`, `FAILED→REQUESTED`(retry), **SENDING→CANCELED 불허** (`PayoutStatus.java:40-54`)
   - Ledger: `PENDING→POSTED→REVERSED`, 엔트리 불변 — 정정은 신규 엔트리 (`LedgerStatus.java:20-26`)
2. **등급별 정책** (`SellerTier.java:11-17`, 정본 스킬과 코드 일치 확인):
   NORMAL 3.5%/T+7/홀드백 30%·30일 · VIP 2.5%/T+3/10%·14일 · STRATEGIC 2.0%/T+1/0%.
   계산 순서: 수수료 차감 → net → 홀드백 분리. 해제일은 영업일 기준.
3. **수수료율 스냅샷** — 생성 시점 요율을 `settlements.commission_rate` 영구 저장, 과거 정산 재계산 없음 (`Settlement.java:38,82`).
4. **역정산 = 조정 트랜잭션** (ADR 0004) — 기존 row 불변, `settlement_adjustments` 음수 행 추가 + 원장 역분개.
5. **복식부기** — 전표 = (차변, 대변, 양수 금액) 쌍, 계정 상이 강제, `balancedPairForSettlement` 균형 팩토리 (`LedgerEntry.java:117,183-188`). POSTED 수정 금지.
6. **멱등 3단** — ① outbox event_id UNIQUE → ② `processed_events` (consumer_group, event_id) PK (prune 보존 ≥7일 가드) → ③ `uk_settlements_payment_id` (결제 1건=정산 1건) (`V1__settlement_baseline.sql:32,202`).

## 이벤트 계약

**소비 6토픽**: `order.created`·`payment.captured`·`payment.refunded`(뷰+조정 컨슈머 2개)·`user.registered`·`product.changed`·`loan.repayment_applied`
**발행 2토픽**: `settlement.created`·`settlement.confirmed`
— JSON Schema 정본 `shared-common/.../contracts/events/` (ADR 0024). 경계 주석: `pgreconciliation.discrepancy_approved` 소비도 정산 조정에 진입하나 해당 서브도메인은 범위 밖.

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 상태머신 4종 전이표 일치 | `:settlement-service:test` 도메인 테스트 |
| AC-2 | 발행 2 + 소비 6토픽 계약 일치 (양방향) | `SettlementEventContractTest` + `EventContractConsumerTest` |
| AC-3 | 헥사고날·프로젝션 경계 위반 0 | `SettlementProjectionArchitectureTest` (ArchUnit) |
| AC-4 | LINE ≥ 90% · 도메인 INSTRUCTION ≥ 80% | `:settlement-service:jacocoTestCoverageVerification` |
| AC-5 | 등급 3종 계산 정본 일치 · 시산표 균형 | 도메인 계산 테스트 + MCP `settlement_simulate` (IT 는 Docker 전제) |
| AC-6 | 도메인 OO 불변식 | `guard.mjs` OO-* + `oo-gate.test.mjs` |

## Known Issues (발견만 기록)

- **KI-1**: `SettlementStatus.fromString` 미지 값 → `REQUESTED` 조용히 폴백 (V26 롤백 방어로 문서화됨).
- **KI-2**: `SellerTier.fromStringOrDefault` 오타 등급 → `NORMAL`(3.5%) 조용히 폴백 — 등급 오염 시 수수료 무경고 기본율.
- **KI-3**: 레거시 `COMMISSION_RATE`(3%) 상수 잔존 — null 레거시 행 폴백 전용, 신규 참조 금지는 규율로만.
