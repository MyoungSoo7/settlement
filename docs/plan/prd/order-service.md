# PRD — 커머스 거래 (order-service)

> **문서 성격**: 구현된 코드에서 **거꾸로 역산한(reverse-engineered) 제품 요구사항 문서**다.
> 자매 문서 [`settlement-core.md`](settlement-core.md)·[`card-service.md`](card-service.md) 와 같은 규약을 쓴다 —
> 새 기능을 제안하지 않고, 이미 동작 중인 시스템이 *무엇을, 왜, 어떤 규칙으로* 하는지를 제품 관점으로 재진술한다.
>
> | 항목      | 값                                                                                            |
> | --------- | ----------------------------------------------------------------------------------------------- |
> | 대상 범위 | `order-service`(8088, DB `opslab`) 전체 — 회원·상품·장바구니·주문·결제·배송·쿠폰·리뷰 + 정합성 부속 |
> | 역산 기준 | 2026-08-13 `develop` 브랜치                                                                   |
> | 근거      | 16개 도메인 컨텍스트, 발행 8토픽, 배치 6종, 테스트 1,360건(로컬 실측)                          |
> | 범위 밖   | 정산 계산(settlement 소관) · 실제 PG 승인 로직(Toss) · 프론트엔드                              |
> | 관련 문서 | [`../../../SPEC.md`](../../../SPEC.md) · `order-commerce-rules` 스킬 · [`order-service-core-commerce.seed.yaml`](../seeds/order-service-core-commerce.seed.yaml) |

---

## 1. 배경과 문제

이 서비스는 **플랫폼의 시작점**이다. 회원이 가입하고, 상품을 담고, 결제하고, 배송받는다. 그리고 그
사건들이 정산·대출·투자·카드의 입력이 된다. 그래서 여기서 틀리면 **하류 전체가 틀린다.**

| 문제                | 구체적 손상                                                                          |
| ------------------- | ------------------------------------------------------------------------------------ |
| **중복 결제·환불**  | 사용자가 버튼을 두 번 누르거나 네트워크가 끊기면 돈이 두 번 나간다                  |
| **재고 오버셀**     | 동시 주문이 같은 재고를 차감하면 없는 물건을 판다                                    |
| **하류 오염**       | 잘못된 주문·결제 사건이 이벤트로 나가면 정산·GL 까지 오염된다                       |

order-service 는 커머스 전 과정을 담되, **돈과 재고에서 동시성·멱등을 구조로 막는다.** 핵심 설계 판단은
하나다 — **하류로 나가는 사건은 되돌릴 수 없다고 가정하고 만든다.**

## 2. 목표 / 비목표

### 2.1 목표

| # | 목표 | 성공 기준 |
|---|---|---|
| G1 | 같은 요청이 두 번 처리되지 않는다 | 주문·환불에 Idempotency-Key |
| G2 | 재고가 음수가 되지 않는다 | 조건부 UPDATE 로 동시 차감 직렬화 |
| G3 | 환불이 원결제를 초과하지 않는다 | 비관적 락 + 누적 검증 |
| G4 | 상태가 임의로 바뀌지 않는다 | 주문·결제 상태머신 강제 |
| G5 | 하류가 신뢰할 사건만 발행한다 | Outbox 로 DB 커밋과 원자적 발행 |
| G6 | 대사로 하류와 맞춰볼 수 있다 | `/internal/recon` 자기 합계 노출 |

### 2.2 비목표

| # | 비목표 | 이유 |
|---|---|---|
| N1 | 정산 계산 | settlement 소관 — 여기는 사건만 발행 |
| N2 | PG 승인 로직 | Toss 소관. 여기는 상태 관리와 멱등 |
| N3 | 조직 단위 권한 | organization 소관 |
| N4 | 이벤트 소비 | 이 서비스는 상류다(recon 만 내부 API 로 응답) |

## 3. 사용자

| 사용자 | 무엇을 위해 쓰는가 |
|---|---|
| **구매자** | 가입·상품 탐색·장바구니·주문·결제·배송 조회·리뷰 |
| **셀러** | 상품·SKU·재고·쿠폰 관리, 주문 처리 |
| **관리자** | 환불 승인, 카테고리·메뉴·공통코드·RBAC 관리 |
| **하류 서비스** | 8토픽 소비, 대사 API 호출 |

## 4. 제품 범위 — 기능 맵

| 영역 | 기능 |
|---|---|
| 회원 | 가입·로그인(JWT 발급)·비밀번호 재설정·멤버십 |
| 상품 | 상품·SKU(variant)·이미지·카테고리 트리·태그 |
| 장바구니/쿠폰 | 담기, 쿠폰 발급·사용(등급별) |
| 주문 | 단건/다건 주문, 재고 차감, Idempotency-Key |
| 결제 | 생성·인증·캡처·환불(분할 포함), PG 라우팅, 관리자 승인 |
| 배송 | 배송 라이프사이클, 지연 스캔 |
| 셀러등급 | 월 1회 등급 평가 → 이벤트 발행 |
| 부가 | 리뷰·게임·메뉴·공통코드·RBAC |
| 정합성 | `/internal/recon`, 프로젝션 백필 |

## 5. 핵심 유스케이스

### UC-1. 중복 주문 제출이 하나로 수렴한다

1. 클라이언트가 `Idempotency-Key` 를 실어 주문을 만든다.
2. 같은 키로 재요청이 와도 주문이 두 번 생기지 않는다.
3. SKU 재고는 **조건부 UPDATE**(`재고 >= 요청수량` 조건이 SQL 안에 있음)로 차감한다 — 조건이 깨지면
   0행이 갱신되고 실패로 처리된다. 락 없이 동시성이 직렬화된다.

### UC-2. 환불이 원결제를 넘지 못한다

1. 관리자 또는 사용자가 환불을 요청한다.
2. 결제를 **비관적 락**으로 잡고 누적 환불액을 검증한다 — 초과면 거부.
3. 환불 **시도 이력**은 본 트랜잭션과 분리된 `REQUIRES_NEW` 트랜잭션으로 커밋한다.

> 왜 분리하는가: 본 트랜잭션이 롤백돼도 "시도했다"는 사실은 남아야 한다. 남지 않으면 재시도·감사에서
> 무슨 일이 있었는지 알 수 없다.

### UC-3. 사건이 하류로 원자적으로 나간다

1. 주문 생성·결제 캡처·환불이 성공하면 **같은 트랜잭션에서** `outbox_events` 에 INSERT 한다.
2. 폴러가 Kafka 로 발행한다 — DB 커밋과 발행이 갈라지지 않는다.
3. 하류(settlement·account·notification·operation)가 이 사건으로 자기 상태를 만든다.

### UC-4. 하류와 숫자를 맞춰본다

1. settlement 가 대사 시 order 의 `/internal/recon` 을 호출한다(공유 시크릿).
2. order 는 **자기 DB 만 읽어** 합계를 돌려준다.
3. 양측이 각자 자기 것만 읽으므로 cross-DB 조인이 0 이다.

### UC-5. 운영 신호를 내보낸다

1. 배송 지연·재고 회수 지연을 주기 스캐너가 감지한다(기본 6시간).
2. `ops.*` 신호를 발행해 operation-service 가 관제 버킷에 누적한다.

## 6. 기능 요구사항

| FR | 요구사항 | 강제 지점 |
|---|---|---|
| FR-1 | 주문·환불은 Idempotency-Key 로 중복을 막는다 | 주문·환불 경로 |
| FR-2 | 재고 차감은 조건부 UPDATE 로 한다 | SKU 차감 쿼리 |
| FR-3 | 환불 누적이 원결제를 초과할 수 없다 | 비관적 락 + 검증 |
| FR-4 | 환불 시도 이력은 독립 트랜잭션으로 남는다 | `RefundLifecycle`(`REQUIRES_NEW`) |
| FR-5 | 주문·결제 상태는 상태머신을 통과해야 바뀐다 | `OrderStatus`·`PaymentStatus` `canTransitionTo` |
| FR-6 | 이벤트는 Outbox 경유로만 발행한다 | `outbox_events` |
| FR-7 | 대사 API 는 공유 시크릿으로 보호한다 | `X-Internal-Api-Key` |
| FR-8 | 셀러 등급은 월 1회 평가해 이벤트를 낸다 | `SellerTierEvaluationScheduler` |
| FR-9 | 만료된 결제를 정리한다 | `PaymentExpiryScanner` |
| FR-10 | 실패한 환불을 재시도한다 | `RefundRetryScheduler` |

## 7. 도메인 규칙 (BR)

| BR | 규칙 | 근거 |
|---|---|---|
| BR-1 | **상태머신이 진실** — 전이표 밖의 상태 변경은 존재하지 않는다 | `canTransitionTo` → `transitionTo` |
| BR-2 | **재고는 조건으로 지킨다** — 읽고-쓰기 사이의 틈을 없앤다 | 조건부 UPDATE |
| BR-3 | **환불 이력은 롤백과 무관하게 남는다** — 시도 사실이 증거다 | `REQUIRES_NEW` |
| BR-4 | **발행은 커밋과 한 몸** — 커밋 실패했는데 이벤트가 나가는 사고를 구조로 막는다 | Outbox |
| BR-5 | **대사는 각자 자기 DB** — 조인이 아니라 API 로 맞춘다 | `/internal/recon` |
| BR-6 | **MSA 경계** — settlement 가 order 를 import 하지 않는다(역방향도 없다) | guard `MSA-BOUNDARY` |

## 8. 데이터 모델 (요지)

| 영역 | 대표 테이블 |
|---|---|
| 회원 | `users`, `memberships`, `password_reset_tokens` |
| 상품 | `products`, `product_variants`, `product_images`, `ecommerce_categories`, `tags` |
| 거래 | `orders`, `order_items`, `carts`, `coupons` |
| 결제 | `payments`, `refunds`, `payment_tenders` |
| 배송 | `shipments` |
| 부가 | `reviews`, `games`, `menus`, `common_codes`, `rbac_*` |
| 공통 | `outbox_events`, `processed_events`, `audit_logs`(파티션드) |

## 9. 인터페이스

### 9.1 REST (대표)

| 경로 | 설명 |
|---|---|
| `/auth`, `/users`, `/memberships` | 인증·회원 |
| `/api/products`, `/products/{id}/variants`, `/api/categories`, `/api/tags` | 상품·분류 |
| `/users/{userId}/cart`, `/coupons` | 장바구니·쿠폰 |
| `/orders`, `/orders/{id}/shipment` | 주문·배송 |
| `/payments`, `/payments/split`, `/api/payments/*/refunds` | 결제·환불 |
| `/admin/refunds`, `/admin/pg`, `/admin/menus`, `/admin/common-codes`, `/admin/rbac` | 관리 |
| `/internal/recon`, `/admin/settlement-projection` | 대사·백필 |

### 9.2 이벤트 (발행 8)

| 이벤트 | 주요 소비처 |
|---|---|
| `OrderCreated` | settlement(프로젝션)·operation |
| `PaymentCreated`·`PaymentCaptured`·`PaymentRefunded` | settlement·account·notification·operation |
| `UserRegistered` | settlement·company |
| `UserMembershipChanged` | settlement |
| `ProductChanged` | settlement(프로젝션) |
| `SellerTierChanged` | settlement(수수료 등급) |

**소비 0** — 상류 서비스다.

## 10. 비기능 요구

| NFR | 요구 | 현재 상태 |
|---|---|---|
| NFR-1 | 커버리지 LINE ≥ 90% | JaCoCo 게이트 |
| NFR-2 | 동시 주문·환불에서 정합 | 조건부 UPDATE + 비관적 락 |
| NFR-3 | MSA 경계 | guard `MSA-BOUNDARY` 3중 |
| NFR-4 | 감사 로그 증가 대비 | 파티션 + 런웨이 |
| NFR-5 | 대사 API 보호 | 공유 시크릿 |

## 11. 배치 (Asia/Seoul)

| 시각/주기 | 작업 |
|---|---|
| 매일 03:20 | 만료 결제 정리 |
| 매월 1일 03:00 | 셀러 등급 평가 |
| 6시간 간격 | 배송 지연 스캔 → `ops.shipping.delayed` |
| 6시간 간격 | 재고 회수 지연 스캔 → `ops.stock.reclaim_delayed` |
| 주기 | 환불 재시도 |
| 매월 1일 02:30 | 감사 파티션 런웨이 |

## 12. 역산에서 드러난 격차

### G-1. 한 서비스가 16개 컨텍스트를 담고 있다

회원·상품·주문·결제·배송·쿠폰·리뷰·게임·메뉴·공통코드·RBAC·셀러등급·대사·백필이 **한 배포 단위**에
있다. 원래 모놀리스에서 분리해 나온 잔여이며, 장애 반경과 배포 단위가 크다. 새 도메인을 추가할 때
**스캔·JPA·gateway·nginx·Dockerfile 5곳을 배선**해야 하는 것도 이 크기의 대가다(누락 시 조용히 404).

### G-2. 결제 상태와 텐더 상태가 이중으로 존재한다

`payments` 와 `payment_tenders`(분할결제)가 각각 상태를 갖는다. 두 상태가 어긋났을 때 무엇이 정본인지,
누가 맞추는지가 이 역산 범위에서 확인되지 않았다.

### G-3. 재고 회수(reclaim)의 정책이 스캐너에만 있다

주문 취소·미결제 만료 시 재고를 언제 되돌리는지가 **스캐너 주기(6시간)** 에 묶여 있다. 그 사이 재고는
"팔린 것도 아니고 살 수 있는 것도 아닌" 상태로 잠긴다. 지연 신호를 내보내지만(`ops.stock.reclaim_delayed`)
그 신호를 받아 자동으로 푸는 경로는 없다.

### G-4. 대사 API 가 공유 시크릿 하나에 의존한다

`/internal/recon` 은 `X-Internal-Api-Key` 로만 보호된다. 키가 유출되면 주문 합계가 노출되고, 키 회전
절차가 문서화돼 있지 않다. gateway 라우팅 여부에 따라 노출 범위가 달라진다.

### G-5. 이벤트 8종의 소비처가 문서에만 있다

어떤 하류가 무엇을 소비하는지는 `../../../SPEC.md` 에 있지만, **소비처가 사라졌을 때 발행측이 알 방법이 없다.**
계약 테스트는 스키마 드리프트를 막지만 "아무도 안 듣는 토픽"은 잡지 못한다.

### G-6. 정합성 부속이 본체와 같은 서비스에 있다

`recon`·`projectionbackfill` 은 **정합성을 검증하는 코드**인데 검증 대상과 같은 배포 단위에 있다.
같은 배포로 함께 나가므로, 검증 코드에 버그가 있으면 대상과 함께 틀린다.

## 13. 추적 항목

| # | 항목 | 상태 |
|---|---|---|
| T-1 | 컨텍스트 분리 로드맵 | 미검토 (G-1) |
| T-2 | 결제/텐더 상태 정본 규칙 | 미확인 (G-2) |
| T-3 | 재고 회수 자동화 | 신호만 존재 (G-3) |
| T-4 | 내부 키 회전 절차 | 문서 없음 (G-4) |
| T-5 | 미소비 토픽 감지 | 없음 (G-5) |
