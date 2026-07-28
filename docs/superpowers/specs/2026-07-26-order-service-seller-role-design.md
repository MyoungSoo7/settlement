# Order Service 판매자 역할과 상품 소유권 설계

## 1. 목적

`order-service`의 사용자 역할을 구매자, 판매자, 플랫폼 운영자, 시스템 관리자로 명확히 분리한다. 상품에는 항상 실제 판매자를 연결하고, 판매자는 자기 상품과 판매 데이터에만 접근하게 한다. 플랫폼 운영자와 시스템 관리자는 판매자를 대신해 상품을 등록할 수 있으며, 개발 환경에서는 실제 커머스 흐름을 검증하기 위한 플랫폼 테스트 상품도 등록할 수 있다.

이 설계는 역할 기반 권한(RBAC)과 리소스 소유권을 별개의 경계로 취급한다.

- 역할은 어떤 작업을 수행할 수 있는지를 결정한다.
- `sellerId`는 어떤 판매자의 데이터에 접근할 수 있는지를 결정한다.

## 2. 역할 정의

| 역할 | 의미 | 구매 | 상품 등록·관리 | 플랫폼 횡단 운영 | 시스템·권한 관리 |
|---|---|---:|---:|---:|---:|
| `USER` | 일반 고객 | 가능 | 불가 | 불가 | 불가 |
| `SELLER` | 입점 판매자 | 불가 | 자기 상품만 가능 | 불가 | 불가 |
| `MANAGER` | 플랫폼 운영자 | 가능 | 모든 판매자 대리 등록·관리 | 가능 | 불가 |
| `ADMIN` | 시스템 관리자 | 가능 | 모든 판매자 대리 등록·관리 | 가능 | 가능 |

역할은 단일 값으로 유지한다. 판매자 계정의 일반 상품 구매는 지원하지 않으며, 운영상 구매 검증이 필요한 경우 `MANAGER` 또는 `ADMIN` 계정을 사용한다.

기존의 `CUSTOMER`, `COMPANY`, `TECHNICIAN` 역할은 다른 도메인과의 호환 범위를 별도 조사한 뒤 유지 또는 마이그레이션한다. 이번 변경에서 임의로 삭제하지 않는다.

## 3. 회원 가입과 역할 부여

커머스 공개 회원 가입 요청은 `USER` 또는 `SELLER`를 선택할 수 있다. 요청자가 `ADMIN`이나 `MANAGER`를 지정하면 `400 Bad Request`로 거부한다. 기존 비커머스 가입 흐름의 `CUSTOMER`, `COMPANY`, `TECHNICIAN`은 이번 변경에서 계속 허용하되, 별도의 기존 승인 규칙을 유지한다. 후속 마이그레이션 전까지 한 엔드포인트를 공유하더라도 허용 역할 목록을 서버가 명시적으로 관리한다.

- `USER`: 가입 즉시 `APPROVED` 상태가 된다.
- `SELLER`: `PENDING` 상태로 가입하고, `MANAGER` 또는 `ADMIN`의 승인을 받은 뒤 상품을 등록할 수 있다.
- `MANAGER`: `ADMIN`만 기존 사용자에게 부여할 수 있다.
- `ADMIN`: 공개 API로 부여하지 않고 시스템 초기화 또는 기존 `ADMIN`의 보안 관리 절차로만 부여한다.

로그인과 모든 보호 API는 `active == true`이면서 `membershipStatus == APPROVED`인 사용자만 허용한다. `PENDING`, `REJECTED`, `SUSPENDED` 또는 비활성 사용자는 토큰 발급 및 서비스 이용을 거부한다.

### 3.1 기존 JWT 즉시 무효화

사용자에 단조 증가하는 `tokenVersion`을 둔다. JWT에는 발급 시점의 `tokenVersion`을 클레임으로 포함한다. 보호 API 요청마다 인증 어댑터가 DB의 현재 사용자를 authoritative source로 조회해 다음 조건을 모두 검증한다.

- JWT의 `tokenVersion`과 현재 사용자의 값이 같다.
- JWT의 역할과 현재 사용자의 역할이 같다.
- 사용자가 활성 상태이고 멤버십이 승인 상태다.

역할 변경, 정지, 비활성화, 비밀번호 강제 초기화 시 `tokenVersion`을 증가시켜 기존 토큰을 즉시 무효화한다. 사용자 상태 변경과 버전 증가는 같은 트랜잭션에서 처리한다. 즉시 무효화 경로에는 비동기 캐시를 사용하지 않는다. 추후 캐시를 도입하려면 동기 invalidation 실패 시 요청을 fail closed하는 별도 설계가 필요하다. 배포 시 `tokenVersion` 클레임이 없는 기존 JWT는 일괄 거부해 다시 로그인하게 한다.

## 4. 상품 소유권 모델

모든 판매 가능한 상품은 유효한 `sellerId`를 가져야 한다. `sellerId`는 승인된 `SELLER` 역할의 사용자를 참조하며, 상품 생성 이후 일반 수정 API로 변경할 수 없다. 판매자 이전이 필요하다면 별도의 관리자 전용 이전 유스케이스와 감사 이력을 사용한다.

상품 생성 시 소유자는 호출자 역할에 따라 서버가 결정한다.

### 4.1 SELLER 등록

`SELLER` 요청에서는 `sellerId` 입력을 받지 않는다. JWT의 `uid`를 상품 소유자로 사용한다. 요청 본문에 판매자 식별자를 넣어 다른 판매자를 사칭하는 경로를 만들지 않는다.

### 4.2 MANAGER/ADMIN 대리 등록

`MANAGER`와 `ADMIN`은 등록 모드를 명시한다.

- `SELLER`: `sellerId`가 필수이며 대상 사용자가 활성·승인된 `SELLER`인지 검증한다.
- `PLATFORM_TEST`: 개발 환경에서만 허용하며 서버 설정의 개발용 테스트 판매자 ID를 사용한다.

대리 등록 시 상품 소유자 `sellerId`와 작업 수행자 `createdBy`를 구분해 감사 로그에 남긴다. 이후 가격, 재고, 상태 변경도 동일하게 수행자와 대상 판매자를 기록한다.

## 5. 개발용 플랫폼 테스트 상품

플랫폼 자체 상품은 실제 플랫폼 매출 상품이 아니라 개발 환경의 종단간 운영 테스트 자산이다. 별도 상품 테이블이나 `sellerId = null`을 사용하지 않고, 개발 프로필에서만 생성되는 전용 `SELLER` 계정에 귀속한다.

- 예시 설정 키: `commerce.platform-test-seller-id`
- 계정은 개발 시드로만 생성한다.
- 운영 프로필에서는 설정 자체를 제공하지 않는다.
- `PLATFORM_TEST` 등록 요청은 개발 프로필이 아니면 `403 Forbidden`으로 거부한다.
- 테스트 상품은 실제 상품과 같은 주문, 재고, Outbox, 결제, 정산 흐름을 통과한다.
- 외부 결제는 개발 환경의 Mock 또는 Sandbox PG를 사용한다.
- 정산·원장·환불 데이터는 개발 DB에만 생성하며 운영 데이터와 섞이지 않는다.

전용 테스트 판매자에 귀속함으로써 상품·결제 이벤트의 `sellerId`를 항상 유지하고, 판매자 등급·정산 주기·홀드백 계산과 `SettlementCreated` 발행까지 실제 경로로 검증한다.

## 6. 인가와 소유권 규칙

URL 역할 매처만으로 판매자 격리를 보장하지 않는다. 애플리케이션 서비스에서 JWT 주체와 리소스 소유자를 대조한다.

### 6.1 상품

- `USER`: 조회만 가능하다.
- `SELLER`: `product.sellerId == principal.userId`인 상품만 생성·수정·재고 변경·상태 변경할 수 있다.
- `MANAGER`, `ADMIN`: 모든 판매자의 상품을 관리할 수 있다.

### 6.2 구매

- `USER`, `MANAGER`, `ADMIN`: 장바구니, 주문 생성, 결제, 본인 주문 조회·취소·환불 신청이 가능하다.
- `SELLER`: 구매 API를 호출할 수 없다.
- 클라이언트가 전달한 `userId`를 구매자 식별자로 신뢰하지 않는다. 구매자는 JWT의 `uid`에서 파생한다.
- `MANAGER`와 `ADMIN`도 일반 구매 API에서는 자기 주문만 다룬다. 타인 주문 처리는 명시적인 관리자 API로 분리한다.

### 6.3 멀티셀러 체크아웃과 단일 결제

장바구니에는 여러 판매자의 상품을 담을 수 있다. 체크아웃은 하나의 `CheckoutGroup` 아래 판매자별 `SellerOrder`를 생성한다. 각 `SellerOrder`는 정확히 하나의 `sellerId`만 가지며, 서로 다른 판매자의 라인이 한 주문에 섞이지 않는다.

고객은 전체 체크아웃 금액을 한 번만 PG 결제한다. 내부 `Payment` 한 건에 판매자 주문별 `PaymentAllocation`을 생성한다.

```text
CheckoutGroup
 ├─ SellerOrder A ─ PaymentAllocation A ─ Settlement A
 └─ SellerOrder B ─ PaymentAllocation B ─ Settlement B
             └──── 단일 Payment / 단일 PG 승인 ────┘
```

다음 금액 불변식을 도메인과 DB 제약으로 강제한다.

- 모든 금액은 `BigDecimal`과 `NUMERIC(19,2)`를 사용한다.
- KRW 배분은 원 단위 정수로 계산하고 필요한 나눗셈에 `RoundingMode.HALF_UP`을 명시한다.
- `Payment.amount == sum(PaymentAllocation.amount)`가 항상 성립해야 한다. 애플리케이션은 결제 행을 잠근 같은 트랜잭션에서 모든 배분을 생성하고 `finalizeAllocations()`로 합계를 검증한다. DB는 커밋 시점에 실행되는 deferred constraint trigger로 같은 불변식을 재검증하며, finalize 이후 배분 변경을 금지한다.
- `PaymentAllocation.amount == SellerOrder.amount`가 성립해야 한다.
- `PaymentAllocation`의 `(order_id, seller_id)`는 `SellerOrder`의 동일 키를 참조하는 composite FK로 소유자 일치를 강제한다.
- 쿠폰 배분 진입 전에 `0 <= couponDiscount <= sum(lineGross)`를 검증한다.
- 쿠폰 할인은 주문 라인별로 먼저 배분한 뒤 판매자 주문별로 합산한다. `(sellerId, productId, variantId, lineId)`로 안정 정렬하고, 마지막 라인을 제외한 각 라인의 할인액을 `remainingDiscount × lineGross ÷ remainingGross`로 `HALF_UP` 계산한 뒤 `0..lineGross` 범위로 제한한다. 마지막 라인은 남은 할인액 전부를 받는다. 따라서 `sum(lineDiscount) == couponDiscount`와 `0 <= lineDiscount <= lineGross`가 항상 성립하며 음수 잔여가 발생하지 않는다.
- 배송비는 판매자 주문 단위로 계산해 해당 배분에만 포함한다.

체크아웃과 판매자 주문 생성은 구매자 범위 멱등 키 아래 원자적으로 처리한다. `UNIQUE(buyer_id, idempotency_key)`를 강제하고 정규화한 요청의 SHA-256 fingerprint를 함께 저장한다. 같은 키·같은 fingerprint 재요청은 저장된 `CheckoutGroup` 응답을 반환하고, 같은 키·다른 fingerprint는 `409 Conflict`로 거부한다. 주문·재고·쿠폰·배분 생성 중 하나라도 실패하면 전체 체크아웃을 롤백한다. PG 승인 후 내부 커밋 실패는 보상 환불과 운영 재처리 대상으로 기록하며, 동일 PG 요청을 재시도할 때 외부 승인과 내부 결제가 중복되지 않도록 PG 멱등 키를 사용한다.

### 6.4 판매 운영

- `SELLER`: 자기 상품에서 발생한 판매 주문, 정산, 세금계산서, 지급 정보만 조회할 수 있다.
- `MANAGER`: 플랫폼 운영 목적으로 판매자 전체를 조회하고 승인된 운영 작업을 수행한다.
- `ADMIN`: 시스템 설정, 역할 부여, 지급·환수·백필 등 고위험 작업을 수행한다.

판매자용 조회·관리 표면은 상품뿐 아니라 상품 이미지, 옵션/SKU, 재고, 자기 판매 주문, 결제 배분, 정산, 세금계산서, 지급 내역을 포함한다. 모든 조회의 판매자 범위는 요청 파라미터가 아니라 JWT `uid`에서 파생한다. `MANAGER`와 `ADMIN`의 횡단 조회는 별도 관리자 API로 분리한다.

## 7. 데이터와 API 변경 방향

### 7.1 데이터 모델

- `UserRole`에 `SELLER`를 추가한다.
- `Product` 도메인과 `ProductJpaEntity`에 `sellerId`를 추가한다.
- 신규 상품의 `seller_id`는 `NOT NULL`로 강제하고 FK 삭제 정책은 `ON DELETE RESTRICT`로 변경한다. 판매자는 물리 삭제하지 않고 비활성화한다.
- 기존 상품은 승인된 `SELLER` 또는 개발 전용 테스트 판매자에게 백필한 뒤 제약을 강화한다. 현재 `MANAGER`에 귀속된 시드 상품은 운영 상품으로 간주하지 않고, 대상 판매자 역할을 검증한 마이그레이션 매핑으로 이전한다.
- 판매자 범위 상품명 유일성 `UNIQUE(seller_id, name)`을 유지한다.
- `checkout_groups`, 판매자 단위 `orders.seller_id`, `payment_allocations`, `refund_allocations`를 추가한다.
- `payment_allocations`는 `id`, `payment_id`, `order_id`, `seller_id`, `amount`, 상태와 감사 시각을 가지며 `UNIQUE(payment_id, order_id)`를 강제한다.
- `orders`에는 `UNIQUE(id, seller_id)`를 두고 `payment_allocations(order_id, seller_id)`가 이를 composite FK로 참조한다.
- `refund_allocations`는 `id`, `refund_id`, `payment_allocation_id`, `amount`와 감사 시각을 가지며 `UNIQUE(refund_id, payment_allocation_id)`를 강제한다.
- 사용자에 `token_version`을 추가한다.

### 7.2 상품 생성 명령

상품 생성 명령은 일반 상품 필드와 소유자 선택을 분리한다.

- `SELLER`용 API: 소유자 입력 없음
- `MANAGER/ADMIN`용 API: `ownerMode`와 조건부 `sellerId`
- `ownerMode=SELLER`: `sellerId` 필수
- `ownerMode=PLATFORM_TEST`: `sellerId` 입력 금지, 개발 프로필에서만 허용

### 7.3 인증 주체

JWT의 `uid`, `role`, `tokenVersion`을 애플리케이션 계층에서 사용할 수 있는 인증 주체 포트로 노출한다. 도메인 객체가 Spring Security에 직접 의존하지 않도록 한다. 인증 어댑터는 현재 사용자 상태와 버전을 검증하고 유효하지 않은 토큰을 `401 Unauthorized`로 거부한다.

### 7.4 판매자별 정산 이벤트

단일 `PaymentCaptured`만으로 판매자를 추론하지 않는다. 결제 CAPTURED 트랜잭션에서 각 `PaymentAllocation`에 대해 `PaymentAllocationCaptured` Outbox 이벤트를 한 건씩 저장한다. 이벤트에는 `allocationId`, `paymentId`, `checkoutGroupId`, `orderId`, `sellerId`, 금액 문자열, 결제 시각, 판매자 등급·정산 주기 스냅샷을 포함한다.

정산 컨슈머는 처리 첫 단계에서 `(consumer_group, event_id)` 멱등 기록을 남기고 같은 트랜잭션에서 정산을 생성한다. 정산의 최종 중복 방어 키는 `payment_allocation_id UNIQUE`로 전환한다. 기존 `payment_id UNIQUE`는 레거시 이벤트 소비가 종료되고 모든 기존 정산에 소스 키를 백필한 뒤 제거한다. 전환 기간에는 레거시 `PaymentCaptured`와 신규 배분 이벤트로 같은 정산이 이중 생성되지 않도록 이벤트 버전과 컨슈머 활성 플래그를 명시적으로 전환한다.

부분·전체 환불은 원 결제 배분을 참조해 판매자 주문별 `RefundAllocation`을 추가한다. 환불 요청 금액은 0보다 커야 한다. 환불 요청 또는 PG 환불 항목의 안정 식별자를 `refundId`로 사용하고 `(refund_id, payment_allocation_id)`로 중복 생성을 차단한다. 환불 트랜잭션은 대상 `PaymentAllocation`을 잠근 뒤 `sum(completed RefundAllocation.amount) + requestedAmount <= PaymentAllocation.amount`를 재확인하고, DB 원자 조건으로 누적 초과 환불을 최종 차단한다.

각 완료 배분은 같은 트랜잭션의 Outbox에 `PaymentAllocationRefunded`를 기록한다. 컨슈머는 `(consumer_group, event_id)` 멱등 체크와 조정 생성을 같은 트랜잭션에서 처리하고, `settlement_adjustments.source_refund_allocation_id UNIQUE`로 중복 음수 조정을 최종 차단한다. 기존 정산 행을 수정하지 않고 각 `paymentAllocationId`에 대응하는 음수 `settlement_adjustments`와 원장 역분개를 추가한다.

### 7.5 감사 이력

기존 `audit_logs`를 사용해 상품 대리 등록과 변경을 기록한다. 감사 레코드는 `resourceType=Product`, `resourceId`, `actorUserId`, `ownerSellerId`, 동작, 변경 전후 요약, trace ID를 가진다. 상품 변경과 감사 기록은 같은 비즈니스 트랜잭션의 Outbox 또는 동일 DB 트랜잭션으로 원자화한다. 감사 기록 실패를 무시하는 비원자 경로는 상품 소유권 변경에 사용하지 않는다.

## 8. 오류 처리

| 조건 | 응답 |
|---|---|
| 공개 가입에서 `MANAGER` 또는 `ADMIN` 요청 | `400 Bad Request` |
| 미승인·정지·비활성 계정 로그인 또는 API 호출 | `403 Forbidden` |
| `tokenVersion` 또는 현재 역할 불일치 JWT | `401 Unauthorized` |
| `SELLER`가 다른 판매자의 상품 변경 | `403 Forbidden` |
| 대리 등록 대상이 존재하지 않거나 `SELLER`가 아님 | `400 Bad Request` |
| 운영 환경에서 `PLATFORM_TEST` 등록 | `403 Forbidden` |
| `SELLER`가 구매 API 호출 | `403 Forbidden` |
| 구매자가 다른 사용자의 주문·장바구니 접근 | `403 Forbidden` |
| 결제 금액과 판매자별 배분 합계 불일치 | `409 Conflict` 후 전체 롤백 |
| 같은 체크아웃 멱등 키에 다른 요청 fingerprint | `409 Conflict` |
| 누적 환불 배분이 원 결제 배분을 초과 | `409 Conflict` |

리소스 존재 여부 노출이 보안상 문제가 되는 조회에서는 소유권 불일치를 `404 Not Found`로 정규화할 수 있다. 동일 API 내에서는 `403`과 `404` 정책을 일관되게 적용한다.

## 9. 테스트 전략

### 9.1 역할 매트릭스 테스트

`USER`, `SELLER`, `MANAGER`, `ADMIN`별로 상품 등록·수정·구매·관리자 API의 허용과 거부를 검증한다. URL 보안 규칙과 애플리케이션 소유권 검사를 각각 테스트한다.

### 9.2 소유권 및 IDOR 테스트

- 판매자 A가 판매자 B의 상품을 변경할 수 없는지 검증한다.
- 사용자 A가 사용자 B의 장바구니·주문·결제에 접근할 수 없는지 검증한다.
- `MANAGER`와 `ADMIN`의 대리 등록이 지정 판매자에게 정확히 귀속되는지 검증한다.
- 상품 이미지·옵션·재고·판매 주문·정산·세금계산서·지급 조회가 모두 판매자 범위를 벗어나지 않는지 검증한다.

### 9.3 가입·승인 테스트

- 공개 가입으로 `ADMIN` 또는 `MANAGER`를 만들 수 없는지 검증한다.
- 미승인 `SELLER`가 로그인하거나 상품을 등록할 수 없는지 검증한다.
- 승인 후 권한이 정상적으로 활성화되는지 검증한다.
- 정지·비활성화·역할 변경 후 기존 JWT가 즉시 거부되는지 검증한다.
- 기존 `CUSTOMER`, `COMPANY`, `TECHNICIAN` 가입·승인 흐름이 유지되는지 회귀 검증한다.

### 9.4 개발용 종단간 테스트

개발 프로필에서 플랫폼 테스트 판매자 상품을 생성하고 다음 흐름을 검증한다.

`상품 등록 → 재고 차감 → 주문 CREATED → 결제 CAPTURED → 주문 PAID → PaymentAllocationCaptured Outbox → 판매자별 정산 생성`

기존 `PaymentCaptured`는 전환 기간 동안 결제 프로젝션 동기화 용도로만 유지하고 신규 정산 생성 트리거로 사용하지 않는다.

운영 프로필에서는 테스트 판매자 시드와 `PLATFORM_TEST` 등록이 모두 비활성인지 검증한다.

### 9.5 멀티셀러 결제·정산 테스트

- 두 판매자 상품을 한 장바구니에서 체크아웃하면 판매자별 주문 두 건과 단일 결제 한 건이 생성되는지 검증한다.
- 결제 총액, 쿠폰 할인·배송비 반영 후 배분 합계가 정확히 일치하는지 검증한다.
- 0원 라인, 1원 할인, 할인액이 라인 합계에 근접한 경우, 동일 금액 다판매자 라인에서 쿠폰 배분 합계와 라인 상한을 검증한다.
- 각 배분 이벤트가 정확히 한 판매자 정산을 만들고 중복 이벤트가 추가 정산을 만들지 않는지 검증한다.
- 판매자별 부분 환불이 원 정산을 수정하지 않고 해당 판매자의 음수 조정과 역분개만 추가하는지 검증한다.
- 동일 환불 API·PG webhook의 재시도와 동시 부분환불에서 `RefundAllocation`, 정산 조정, 역분개가 중복되지 않고 누적 환불이 원 배분을 넘지 않는지 검증한다.
- 체크아웃 재시도, PG 타임아웃, 일부 내부 실패에서 중복 주문·결제·정산이 생기지 않는지 검증한다.
- 같은 구매자·멱등 키의 동일 요청은 기존 응답을 반환하고 다른 fingerprint는 거부하며, 다른 구매자의 같은 문자열 키는 독립적으로 처리하는지 검증한다.

## 10. 마이그레이션 순서

1. 공개 회원가입에서 `MANAGER`·`ADMIN`을 차단하고 로그인 상태·`tokenVersion` 검사를 적용한다.
2. `SELLER` 역할과 승인 흐름을 추가하되 기존 비커머스 역할 가입을 회귀 검증한다.
3. 상품 `seller_id`를 nullable 상태로 애플리케이션 모델에 연결한다(expand).
4. 기존 상품을 승인된 판매자에게 백필하고 NULL·역할·참조 무결성을 검증한다(backfill/verify).
5. FK를 `ON DELETE RESTRICT`로 교체하고 `seller_id NOT NULL`을 활성화한다(contract).
6. 상품 전체 표면과 구매 흐름에 역할 및 JWT 기반 소유권 검사를 적용한다.
7. `CheckoutGroup`, 판매자별 주문, 단일 결제와 `PaymentAllocation`을 추가한다.
8. 배분 Outbox 이벤트와 `payment_allocation_id` 기반 정산 멱등 키를 expand→dual-read 검증→consumer cutover→legacy constraint 제거 순으로 전환한다.
9. 개발용 테스트 판매자 시드와 `PLATFORM_TEST` 등록 모드를 추가한다.
10. 전체 역할 매트릭스, IDOR, 멀티셀러 금액·환불·이벤트·정산 종단간 테스트를 통과시킨다.

## 11. 제외 범위

- 하나의 판매자 조직에 여러 직원을 연결하는 조직 멤버십 모델
- 한 사용자가 여러 역할을 동시에 갖는 다중 역할 모델
- 운영 환경의 플랫폼 직매입·직판매 상품
- 판매자 간 상품 소유권 이전의 상세 워크플로
- 기존 비커머스 역할의 제거 또는 전면 마이그레이션(현재 가입·승인 동작은 유지)

이 항목들은 실제 요구가 생길 때 별도 설계로 확장한다.
