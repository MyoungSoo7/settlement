# Seed — order-service 핵심 커머스 루프 as-is 사양

> **상태: CONFIRMED** (Restate 게이트 승인 완료, 2026-07-18) · 정본 데이터: [`order-service-core-commerce.seed.yaml`](./order-service-core-commerce.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화. 2026-07-18, 인터뷰 세션 `interview_20260718_093314`.

## Goal (한 줄)

**order-service 핵심 커머스 루프(주문·결제·환불·재고·쿠폰 + 5개 발행 이벤트 계약)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 계약 드리프트 게이트 ·
면접/포트폴리오 문서 · 후속 기능 베이스로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| order (생성·상태머신·멱등 제출) | cart · shipping · review · game · menu · rbac · commoncode · category 내부 |
| payment (결제·분할 tender·환불) | user 도메인 내부 (가입/멤버십 — `user.registered` 계약 표면만) |
| product 재고 차감/복원 (조건부 UPDATE 경로) | product 도메인 내부 (상품 관리 — `product.changed` 계약 표면만) |
| coupon 적용/사용 제한 | recon · projectionbackfill |
| 발행 이벤트 계약 표면 5토픽 | |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **Order 상태머신** — 11개 상태, 전이표는 `OrderStatus.java:41-54` 가 단일 출처.
   환불 완료 종단은 `REFUNDED` 로 일원화, 결제 후 모든 진행 단계(배송 포함)에서 환불 진입 허용.
   종단(`CANCELED`·`REFUNDED`)에서 추가 전이 0.
2. **Payment 상태머신** — `READY→AUTHORIZED→CAPTURED→REFUNDED`, `AUTHORIZED→CANCELED` (`PaymentStatus.java:22-37`).
3. **환불 3중 방어** — payment 행 `FOR UPDATE`(PESSIMISTIC_WRITE) · 락 전 스냅샷 + 락 내 재확정 +
   도메인 최종 방어선으로 초과 환불 차단 · 환불 이력은 락 획득 前 REQUIRES_NEW INSERT(데드락 회피).
   부분 환불은 tender 단위 잔여액 검증으로 허용.
4. **주문 멱등** — `Idempotency-Key` 헤더 → 분산 락 + DB UNIQUE 2겹, 충돌 시 기존 주문 replay.
5. **재고** — 원자적 조건부 UPDATE(`stock >= q` 가드), 0 도달 시 OUT_OF_STOCK 자동 전이, 복원은 역연산.
6. **쿠폰** — `usedCount >= maxUses` 차단, 사용 시 증가.

## 발행 이벤트 계약 (5토픽)

`lemuel.order.created` · `lemuel.payment.captured` · `lemuel.payment.refunded` ·
`lemuel.user.registered` · `lemuel.product.changed`
— Outbox 컨벤션 라우팅, JSON Schema 정본은 `shared-common/src/testFixtures/resources/contracts/events/` (ADR 0024).

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 상태머신 전이표 일치 | `:order-service:test` 도메인 테스트 |
| AC-2 | 이벤트 5토픽 계약 일치 | 계약 테스트 4클래스 (드리프트 시 빌드 실패) |
| AC-3 | 헥사고날 의존 방향 위반 0 | `HexagonalArchitectureTest` (ArchUnit) |
| AC-4 | LINE ≥ 90% · 도메인 INSTRUCTION ≥ 80% | `:order-service:jacocoTestCoverageVerification` |
| AC-5 | 동시 환불에서 초과 환불 0건 | 환불 동시성 IT (Docker 필요) |
| AC-6 | 도메인 OO 불변식 (setter 0 등) | `guard.mjs` OO-* + `oo-gate.test.mjs` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1**: `OrderStatus.fromString` 파싱 실패 시 예외 대신 `CREATED` 조용히 반환 (`OrderStatus.java:69-75`) — silent default.
- **KI-2**: `REFUND_COMPLETED` 가 `@Deprecated` 로 잔존 (DB 과거 행 호환, by-design).
