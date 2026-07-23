# Seed — investment-service 투자점수·투자주문 as-is 사양

> **상태: CONFIRMED** (2026-07-19) · 정본 데이터: [`investment-service-scoring-order.seed.yaml`](./investment-service-scoring-order.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화. 자매 Seed: [settlement](./settlement-service-accounting-core.seed.md) (재원의 원천 = 확정 정산금).

## Goal (한 줄)

**investment-service(CEO 투자하기 — 투자점수 3축·등급·투자주문·재원 검증)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화한다.**

## 범위

| 포함 | 제외 |
|------|------|
| 투자점수 (3축 밴드·AAA~CCC·적격 경계 ≥60) | 초보 투자 체크·추천 스크리닝 내부 |
| 투자주문 (상태머신·스냅샷·수동 멱등) | 외부 4서비스 클라이언트 상세 |
| 재원 (funding_view·FOR UPDATE 직렬화) | |
| 발행 1 + 소비 1토픽 계약 표면 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **주문 상태머신** — `REQUESTED→APPROVED|REJECTED|CANCELED, APPROVED→EXECUTED|CANCELED`, 종단 3종. 도메인 메서드로만 전이, 위반 시 타입 예외(400), DB CHECK 5상태 (`InvestmentOrderStatus.java:29-42`).
2. **투자점수 3축** — 100 = 수익성 35 + 안정성 35 + 성장성 30, 밴드 단일 조회 지점 `bandScore()`. null/미매칭 0점(보수적), 전년 부재 시 성장성 중립 15점 (`InvestmentScorePolicy.java:62-79`).
3. **등급/적격** — ≥90 AAA ~ ≥40 B, 미만 CCC. **적격 = 총점 ≥60 (BBB+)** (`InvestmentGrade.java:30-44`).
4. **재원 공식** — `available = 확정 정산금 합 − EXECUTED 집행 합`, 신청·집행 모두 FOR UPDATE 직렬화 + @Version (`SellerFunding.java:14-29`).
5. **집행 순서** — 소유권 검증(403) → 재원 재검증(부족 시 REJECTED 확정 커밋 + 422) → approve→execute→발행 (`ExecuteInvestmentOrderService.java:48-84`).
6. **스냅샷** — 신청 시점 score/grade 영구 보존, DB CHECK 0~100.
7. **수동 멱등** — Idempotency-Key 선점(ON CONFLICT DO NOTHING), 중복 409, replay 미지원.

## 이벤트 계약

**발행 1**: `investment.executed` (Outbox) → account·notification 소비.
**소비 1**: `settlement.confirmed` (group `lemuel-investment`, 기본 OFF — `app.kafka.enabled`) → funding_view UPSERT.
멱등 3단: outbox UNIQUE → processed_events PK → `seller_funding_view.settlement_id` PK.

## 수용 기준 (게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 상태머신·타입 예외 일치 | `:investment-service:test` |
| AC-2 | 발행 1 + 소비 1토픽 계약 일치 | `InvestmentEventContractTest` + `EventContractConsumerTest` |
| AC-3 | 헥사고날·타 도메인 미의존 위반 0 | `InvestmentArchitectureTest` |
| AC-4 | LINE ≥ 90% | `:investment-service:jacocoTestCoverageVerification` |
| AC-5 | 3축 밴드·등급 경계·재원 공식 일치 | `InvestmentScorePolicyTest` 등 경계 전수 |
| AC-6 | 동시 초과집행 0 · 멱등 409 | Testcontainers IT 2종 (Docker 전제) |

## Known Issues (발견만 기록)

- **KI-1**: 도메인 INSTRUCTION 80% 엄격 목록에 `investment.domain` 미포함 — 게이트 비대칭.
- **KI-2**: `app.kafka.enabled` 기본 false — 운영 미주입 시 재원 프로젝션 조용히 중단.
- **KI-3**: 레거시 `request()` 가 시스템 존 `now()` 직호출 (신규 경로는 Clock 주입).
- **KI-4**: 추천 유니버스 하드코딩 + 마이그레이션에 추천 세트 박제.
- **KI-5**: 문서 드리프트 — 스킬이 IllegalStateException 표기(실제 타입 예외 400), FOR UPDATE·@Version·수동 멱등 미기재.
