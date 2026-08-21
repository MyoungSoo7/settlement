# Seed — order-service 핵심 커머스 루프 as-is 사양

> **상태: CONFIRMED** (Restate 게이트 승인 완료, 2026-07-18) · 정본 데이터: [`order-service-core-commerce.seed.yaml`](order-service-core-commerce.seed.yaml)
> Ouroboros 방법론(Interview → Seed)으로 결정화. 2026-07-18, 인터뷰 세션 `interview_20260718_093314`.
>
> **개정 이력**
>
> | 판   | 일자       | 대조 기준             | 바뀐 것                                                                 |
> | ---- | ---------- | --------------------- | ----------------------------------------------------------------------- |
> | v1   | 2026-07-18 | 인터뷰 세션           | 최초 결정화                                                             |
> | v1.1 | 2026-08-22 | `develop` `92d25c463` | Payment 상태머신 7상태(EXPIRED 추가)·계약 표면 5→16토픽·AC-2·KI-3 추가 |
>
> v1.1 은 **사양을 바꾼 것이 아니라 코드가 이동한 만큼 기술을 따라 옮긴 것**이다. as-is 원칙은 유지된다 —
> 결함은 여전히 교정하지 않고 Known Issues 로만 기록한다.

## Goal (한 줄)

**order-service 핵심 커머스 루프(주문·결제·환불·재고·쿠폰 + 16개 발행 이벤트 계약)의 현행 동작을
실행 가능한 게이트에 매핑된 불변 사양으로 결정화해, 회귀 기준선 · 계약 드리프트 게이트 ·
면접/포트폴리오 문서 · 후속 기능 베이스로 사용한다.**

## 범위

| 포함 | 제외 |
|------|------|
| order (생성·상태머신·멱등 제출) | cart · shipping · review · game · menu · rbac · commoncode · category 내부 |
| payment (결제·분할 tender·환불) | user 도메인 내부 (가입/멤버십 — `user.registered` 계약 표면만) |
| product 재고 차감/복원 (조건부 UPDATE 경로) | product 도메인 내부 (상품 관리 — `product.changed` 계약 표면만) |
| coupon 적용/사용 제한 | recon · projectionbackfill |
| 발행 이벤트 계약 표면 16토픽 | point · giftcard 원장 내부 (로트·선점·정책 — 계약 표면만 포함) |
| | bulkorder · auditconsole · sellertier 내부 |

## 핵심 불변식 (as-is, 파일:라인 근거)

1. **Order 상태머신** — 11개 상태, 전이표는 `OrderStatus.java:41-54` 가 단일 출처.
   환불 완료 종단은 `REFUNDED` 로 일원화, 결제 후 모든 진행 단계(배송 포함)에서 환불 진입 허용.
   종단(`CANCELED`·`REFUNDED`)에서 추가 전이 0.
2. **Payment 상태머신** — 7상태, 전이 판정은 `PaymentStatus.canTransitionTo`(`PaymentStatus.java:27-43`) 가
   단일 출처다. `READY→AUTHORIZED→CAPTURED→REFUNDED` 주경로에 `AUTHORIZED→CANCELED`(승인취소)와
   **`READY→EXPIRED`**(가상계좌·무통장 입금 기한 경과 — 승인 前 종단)가 갈라진다.
   `FAILED` 는 도메인 전이 메서드가 없어 어떤 전이의 원천도 대상도 아니다.
   종단 4종(`FAILED`·`CANCELED`·`REFUNDED`·`EXPIRED`)에서 추가 전이 0.
   *(v1 은 4상태 + `AUTHORIZED→CANCELED` 로 기술했다. `EXPIRED` 는 가상계좌 입금 대기 도입과 함께 들어왔다.)*
3. **환불 3중 방어** — payment 행 `FOR UPDATE`(PESSIMISTIC_WRITE) · 락 전 스냅샷 + 락 내 재확정 +
   도메인 최종 방어선으로 초과 환불 차단 · 환불 이력은 락 획득 前 REQUIRES_NEW INSERT(데드락 회피).
   부분 환불은 tender 단위 잔여액 검증으로 허용.
4. **주문 멱등** — `Idempotency-Key` 헤더 → 분산 락 + DB UNIQUE 2겹, 충돌 시 기존 주문 replay.
5. **재고** — 원자적 조건부 UPDATE(`stock >= q` 가드), 0 도달 시 OUT_OF_STOCK 자동 전이, 복원은 역연산.
6. **쿠폰** — `usedCount >= maxUses` 차단(`Coupon.java:97`), 사용 시 증가(`:151`).
   *(v1.1 신규)* 주문 취소·환불 시 **회수**로 `usedCount` 감소(`:163`) — 단 `usedCount <= 0` 이면
   감소하지 않는다. 음수가 남으면 한도 계산이 영구히 헐거워지기 때문이다.

> **v1.1 라인 재대조** — 인용한 파일:라인은 전부 현행 코드로 다시 확인했다. 이동한 것:
> `RefundPaymentUseCase` 는 `application/service/` → `application/` 로 옮겨졌고(초과 검증 :129 / 락 내 재확정 :155),
> 도메인 최종 방어선은 `PaymentDomain.java:168,221`, 쿠폰은 위와 같다.
> 이동하지 않은 것: `OrderStatus.java:41-54`·`:69-75`, `PaymentTender.java:103`,
> `PaymentJpaRepository.java:32`, `SpringDataProductJpaRepository.java:42-43`.

## 발행 이벤트 계약 (16토픽)

Outbox 컨벤션 라우팅, JSON Schema 정본은
`../../../shared-common/src/testFixtures/resources/contracts/events` (ADR 0024).
**16토픽 전부 스키마가 존재하며**, 전송 속성(파티션·순서키·보존)은 ADR 0035 카탈로그가 정본이다.

| 묶음                | 토픽                                                                                   |
| ------------------- | -------------------------------------------------------------------------------------- |
| v1 계약 표면 (5)    | `lemuel.order.created` · `lemuel.payment.captured` · `lemuel.payment.refunded` · `lemuel.user.registered` · `lemuel.product.changed` |
| 셀러등급 (1)        | `lemuel.seller.tier_changed`                                                            |
| 포인트 원장 (6)     | `lemuel.point.{charged,granted,used,restored,expired,revoked}`                          |
| 기프트카드 원장 (4) | `lemuel.giftcard.{registered,used,restored,expired}`                                    |

**카탈로그 미등재 3종** — `PaymentCreated`·`PaymentAuthorized`(레거시, 미참조) ·
`UserMembershipChanged`(소비자 생기면 편입). 발행 코드는 살아 있다(→ KI-3).

## 수용 기준 (실행 가능 — 게이트 매핑)

| AC | 기준 | 게이트 |
|----|------|--------|
| AC-1 | 상태머신 전이표 일치 | `:order-service:test` 도메인 테스트 |
| AC-2 | 이벤트 16토픽 계약 일치 | 이벤트 계약 테스트 7클래스 — `{Order,Payment,User,Product,SellerTier,Point,GiftCard}EventContractTest` (드리프트 시 빌드 실패) |
| AC-3 | 헥사고날 의존 방향 위반 0 | `HexagonalArchitectureTest` (ArchUnit) |
| AC-4 | LINE ≥ 90% · 도메인 INSTRUCTION ≥ 80% | `:order-service:jacocoTestCoverageVerification` |
| AC-5 | 동시 환불에서 초과 환불 0건 | 환불 동시성 IT (Docker 필요) |
| AC-6 | 도메인 OO 불변식 (setter 0 등) | `guard.mjs` OO-* + `oo-gate.test.mjs` |

## Known Issues (발견만 기록 — 사양은 as-is 유지)

- **KI-1**: `OrderStatus.fromString` 파싱 실패 시 예외 대신 `CREATED` 조용히 반환 (`OrderStatus.java:69-75`) — silent default. *(v1.1 재확인: 라인 위치·동작 동일)*
- **KI-2**: `REFUND_COMPLETED` 가 `@Deprecated` 로 잔존 (DB 과거 행 호환, by-design).
- **KI-3** *(v1.1 신규)*: 발행 3종(`PaymentCreated`·`PaymentAuthorized`·`UserMembershipChanged`)에
  **소비자가 없다.** 토픽 카탈로그가 "레거시, 어느 서비스도 참조하지 않음"·"소비자가 생기면 편입"으로
  분류해 등재에서 제외했는데, 발행 코드는 그대로라 매 결제·멤버십 변경마다 아무도 읽지 않는 Outbox
  행이 쌓인다. 계약 테스트는 스키마 드리프트만 보므로 이 상태를 잡지 못한다(as-is 유지).
