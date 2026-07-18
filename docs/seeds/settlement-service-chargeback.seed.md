# Seed — settlement-service chargeback(카드사 분쟁) as-is 사양

> **상태: CONFIRMED** (2026-07-19) · 정본 데이터: [`settlement-service-chargeback.seed.yaml`](./settlement-service-chargeback.seed.yaml)
> 부모 Seed: [settlement-service 회계 코어 루프](./settlement-service-accounting-core.seed.md) (조정 트랜잭션 원칙 ADR 0004 공유)

## Goal (한 줄)

**settlement-service chargeback 서브도메인(카드사 분쟁 접수·운영자 결정·셀러 환수 조정)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 면접 문서 ·
Phase 3 확장(사전분쟁 백필·ReversePayout)의 베이스로 사용한다.**

## Refund 와의 구분 (도메인 정체성)

| | Refund | Chargeback |
|---|---|---|
| 개시 | 고객 → 셀러 환불 요청 | 고객 → **카드사** 신고 |
| 흐름 | 셀러 응답 → 환불 처리 | 카드사가 PG 강제 차감 → PG 가 운영사 통지 |
| 회계 | payment 환불 + 역정산 조정 | **별개 원장** — ACCEPT 시에만 셀러 환수 조정 |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **상태머신** — `OPEN → ACCEPTED | REJECTED` 만, 둘 다 종단(재결정·재오픈 불가, 재오픈은 새 row) (`ChargebackStatus.java:18-24`)
2. **도메인 불변식** (`Chargeback.java`) — amount 양수(도메인+DB CHECK 이중), 결정엔 `decidedBy` 필수(REJECT 는 사유도), PG_WEBHOOK 은 `pgChargebackId` 필수, 종단 후 `linkSettlement` 금지, open/rehydrate 봉인 팩토리
3. **결정과 회계의 분리** — 도메인은 결정만, 회계 효과는 서비스가 `SaveSettlementAdjustmentPort` 로만 (Settlement 도메인 모델 직접 import 없음 — 컨텍스트 경계). ACCEPT+settlementId 시 음수 조정 1건(`SettlementAdjustment.ofChargeback`, `amount.negate`), REJECT 는 정산 영향 0
4. **멱등 2겹** — PG_WEBHOOK 중복 통지는 `pgChargebackId` 조회 멱등 + DB 부분 UNIQUE(`idx_chargebacks_pg_id_unique`), 중복 역정산은 `uq_adjustments_chargeback_id` (분쟁 1건=조정 1건)
5. **API** — `/admin/chargebacks` 5개 엔드포인트 (등록·조회 2·accept·reject), 관리자 게이트

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 상태머신·불변식 일치 | `ChargebackTest` |
| AC-2 | ACCEPT 조정 1건·REJECT 0건·웹훅 멱등 | `ChargebackServiceTest` |
| AC-3 | API 표면 5개 일치 | `ChargebackAdminControllerTest` |
| AC-4 | LINE ≥ 90% | `:settlement-service:jacocoTestCoverageVerification` |
| AC-5 | 도메인 OO 불변식 | `guard.mjs` OO-* + `oo-gate.test.mjs` |

## Known Issues (발견만 기록)

- **KI-1 (Phase 3 갭)**: 정산 前 분쟁 ACCEPT 시 조정 미생성 — "정산 생성 시 백필" 주석만 있고 미구현, `linkSettlement` 호출 트리거 미배선 → 해당 경로 셀러 환수 누락 가능.
- **KI-2 (Phase 3 갭)**: Payout COMPLETED 후 환수(ReversePayout) 미구현 (명시적 스코프 아웃).
- **KI-3**: 웹훅 멱등이 check-then-act — 동시 중복 통지 시 두 번째가 UNIQUE 위반 예외 경로 가능(데이터 정합은 DB 가 보장).
