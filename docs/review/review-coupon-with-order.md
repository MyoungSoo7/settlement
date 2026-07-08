# 리뷰 반영: 쿠폰 ↔ 주문 트랜잭션 결합

> 외부 코드 리뷰 피드백 **"쿠폰 실패 시 주문 취소/롤백 흐름 ❌"** 에 대한 진단 및 수정 기록.
> 대상 모듈: `order-service` (commerce 컨텍스트의 `order` / `coupon` 도메인)
> 작성일: 2026-06-25

---

## 1. 리뷰어 지적 (원문 요약)

> 쿠폰 검증과 사용 로직은 존재하지만 **주문 생성 흐름에 `couponCode` 가 연결되어 있지 않아**,
> 쿠폰 유효성 실패로 주문이 취소되거나 롤백되는 시나리오는 아직 완성되지 않았습니다.
>
> 처방: 주문 생성 요청에 `couponCode` 를 포함하고, **상품 합계 계산 → 쿠폰 검증 → 할인 금액 계산 →
> 주문 저장 → 쿠폰 사용 기록** 을 하나의 `@Transactional` 메서드 안에서 처리하면 쿠폰 실패 시
> 주문이 저장되지 않는 구조를 만들 수 있습니다.

**판정: 유효(Valid).** 코드로 확인한 결과 지적이 정확했고, 오히려 구조적으로 더 분리돼 있었다.

---

## 2. 수정 전 진단 (코드 증거)

| 확인 항목 | 결과 |
|-----------|------|
| `order` 패키지 내 `coupon` 참조 | **0건** (grep) |
| `CreateOrderService` / `CreateMultiItemOrderService` 의 쿠폰 사용 | 없음 — `couponCode` 파라미터·할인 계산 전무 |
| `CouponService.validateCoupon` / `useCoupon` 호출처 | **오직 `CouponController`** (별도 HTTP 엔드포인트) |
| `useCoupon(code, userId, orderId)` 의 `orderId` 출처 | **요청 바디** (`CouponUseRequest.getOrderId()`) |

### 무엇이 문제였나

`useCoupon` 이 `orderId` 를 요청 바디로 받는 구조라는 건, 호출 순서가 다음과 같이 **분리된 두 트랜잭션**이라는 뜻이었다.

```
1. POST /orders/multi    → 주문 INSERT 커밋 완료 (정가, 할인 미반영)
2. POST /coupons/use     → 별도 트랜잭션에서 쿠폰 사용 기록
```

이 경우 2번이 실패(한도 초과 / 1인 1매 중복 / 만료)해도 **롤백되는 건 쿠폰 트랜잭션뿐**이고,
1번 주문은 이미 커밋되어 그대로 남는다. 결과적으로:

- 할인이 주문 `amount` 에 **애초에 반영되지 않음** (주문 합계 계산에 쿠폰이 안 들어감)
- "쿠폰 적용했다고 생각한 주문이 정가로 저장" 되거나, 쿠폰 실패에도 주문은 살아남는 불일치

> 부품(`validateCoupon` / `useCoupon`)은 잘 만들어져 있었으나 **주문 흐름에 조립되지 않은** 상태였다.
> (참고: `CouponService` 내부의 `incrementUsageIfAvailable` 조건부 UPDATE + `coupon_usages(coupon_id, user_id)`
> UNIQUE + `DataIntegrityViolationException` 멱등 처리는 동시성 방어가 이미 탄탄했다.)

---

## 3. 수정 내용

쿠폰 검증·할인·사용 기록을 **다건 주문 생성 서비스의 단일 `@Transactional`** 안으로 끌어들였다.

### 변경 파일

| 파일 | 변경 |
|------|------|
| `order/domain/Order.java` | 할인 반영 팩토리 `createMultiItem(userId, items, discountAmount)` 추가. `amount = subtotal - discount`, 음수/소계 초과 할인 가드. 기존 2-arg 는 할인 0 으로 위임(동작 보존) |
| `order/application/port/in/CreateMultiItemOrderUseCase.java` | `create(userId, lines, couponCode)` 추가, 기존 `create(userId, lines)` 는 `couponCode=null` 위임 default 메서드로 보존 |
| `order/application/service/CreateMultiItemOrderService.java` | `CouponUseCase` 주입. 소계 산출 → (쿠폰 있으면) 검증 → 할인 반영 주문 저장 → `useCoupon(code, userId, savedOrderId)`. 검증 실패 시 `CouponApplicationException` |
| `order/adapter/in/web/OrderController.java` | `MultiItemOrderRequest` 에 `couponCode`(nullable) 추가 후 전달 |

### 핵심 흐름 (수정 후)

```java
// CreateMultiItemOrderService.create(userId, lines, couponCode)  — @Transactional
BigDecimal subtotal = Σ items.lineAmount;
BigDecimal discount = BigDecimal.ZERO;
if (couponCode 있음) {
    var r = couponUseCase.validateCoupon(couponCode, userId, subtotal);
    if (!r.valid()) throw new CouponApplicationException(r.message());  // ← 롤백
    discount = r.discountAmount();
}
Order saved = saveOrderPort.save(Order.createMultiItem(userId, items, discount));
if (couponCode 있음)
    couponUseCase.useCoupon(couponCode, userId, saved.getId());          // ← 실패 시 전부 롤백
publishOrderCreated(...);   // Outbox (같은 트랜잭션)
```

### 트랜잭션 경계가 성립하는 이유

- `CreateMultiItemOrderService` 는 `@Transactional`(REQUIRED). 내부에서 호출하는
  `CouponService.validateCoupon`(`readOnly=true`) · `useCoupon`(REQUIRED) 은 **같은 트랜잭션에 합류**한다.
- 따라서 `useCoupon` 이 한도 초과/중복으로 예외를 던지면 주문 INSERT·**SKU 재고 차감**·Outbox 발행까지
  하나로 롤백 → "쿠폰 못 썼는데 주문만 생성" 불일치가 원천 차단된다.
- `validateCoupon` 실패는 `CouponApplicationException`(← `IllegalArgumentException`) 으로 던져
  `OrderExceptionHandler` 가 **400 Bad Request** 로 매핑.

---

## 4. 설계 결정 — 왜 스키마를 바꾸지 않았나

`amount` 를 **할인 후 결제 금액**으로 저장하되, 할인액 전용 컬럼은 추가하지 않았다.

- 소계(subtotal) = `Σ order_items.line_amount` 는 **이미 영속**된다.
- 따라서 `discount = subtotal - amount` 로 **항상 역산 가능** → 별도 컬럼은 중복.
- 쿠폰–주문 연결 자체는 기존 `coupon_usages.order_id` 가 보존.
- 도메인 불변식은 `amount = (Σ line_amount) - 할인` 으로 갱신(영수증↔결제↔정산 정합성 유지).

→ **Flyway 마이그레이션 불필요**, 변경 면적 최소화. settlement 프로젝션이 소비하는 `OrderCreated`
이벤트의 amount 도 할인 후 금액(= 실제 청구액)이 되어 정산 기준이 올바르게 정렬된다.

---

## 5. 검증

`./gradlew :order-service:test --rerun-tasks` (해당 클래스)

| 테스트 | 결과 |
|--------|------|
| `OrderMultiItemTest` | **8/8** (할인 반영/0·null 보존/소계초과·음수 가드 신규 4건 포함) |
| `CreateMultiItemOrderServiceTest` | **9/9** (쿠폰 성공/검증실패 롤백/사용실패 전파/무쿠폰 신규 4건 포함) |
| `HexagonalArchitectureTest` | **4/4** (order→coupon `port.in` import 가 헥사고날 규칙 위반 아님 확인) |

신규 시나리오 테스트 요지:
- 쿠폰 검증 성공 → `amount = 소계 - 할인`, `validateCoupon` 은 소계 기준 호출, `useCoupon` 은 저장된 `orderId` 로 호출
- 쿠폰 검증 실패 → 예외 + `saveOrderPort.save` / `useCoupon` / 이벤트 발행 **모두 미수행**
- `useCoupon` 실패(한도) → 예외 전파(트랜잭션 롤백), 이벤트 발행 미도달

---

## 6. 남은 범위 / 후속

- **단건 주문(`CreateOrderService`)** 에는 쿠폰 결합을 적용하지 않았다(다건/장바구니 경로가 실사용 흐름). 필요 시 동일 패턴으로 확장.
- 통합 테스트(Testcontainers)로 "쿠폰 사용 실패 시 주문 row 가 실제로 롤백되는가" 를 DB 수준에서 1건 보강하면 트랜잭션 경계 증명이 완결된다. (현재는 단위 테스트의 상호작용 검증까지)
- 후속 리뷰 항목: `maxDiscountAmount` 영속 유실 여부(②), 옵션 없는 일반 상품 재고 차감 경로(③).
