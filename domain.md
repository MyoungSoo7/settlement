# 도메인 분석 결과

## 아키텍처 개요

- **프레임워크**: Spring Boot (백엔드) + React/TypeScript (프론트엔드)
- **아키텍처 패턴**: Hexagonal Architecture (Ports & Adapters / Clean Architecture)
- **각 도메인 구조**:
  ```
  domain/              ← 순수 POJO, Enum, 도메인 예외
  application/
    port/in/           ← Use Case 인터페이스 (인바운드 포트)
    port/out/          ← Repository/외부서비스 인터페이스 (아웃바운드 포트)
    service/           ← Use Case 구현체
  adapter/
    in/web/            ← REST Controller
    in/batch/          ← Spring Batch Job & Scheduler
    in/event/          ← Spring Event Listener
    out/persistence/   ← JPA Entity, Spring Data Repository, Persistence Adapter
    out/external/      ← 외부 서비스 Adapter (PG, Email 등)
    out/search/        ← Elasticsearch Adapter
  ```

---

## 전체 도메인 목록 (17개)

| # | 도메인 | 패키지 | 핵심 역할 |
|---|--------|--------|-----------|
| 1 | User | `user` | 회원가입, 로그인, 비밀번호 재설정, 소셜 로그인 |
| 2 | Order | `order` | 주문 생성, 상태 관리 |
| 3 | Payment | `payment` | 결제 처리, PG 연동, 환불 |
| 4 | **Settlement** | `settlement` | **정산 (핵심 도메인)** — 수수료 계산, 배치 처리, CQRS |
| 5 | Product | `product` | 상품 CRUD, 재고 관리, 상품 이미지 |
| 6 | Category | `category` | 이커머스 카테고리 (3단계 계층) |
| 7 | Coupon | `coupon` | 쿠폰 발급, 검증, 할인 계산 |
| 8 | Review | `review` | 상품 리뷰 (1인 1리뷰) |
| 9 | Cart | `cart` | 장바구니 |
| 10 | Shipping | `shipping` | 배송 관리, 배송지 관리 |
| 11 | Notification | `notification` | 알림 (Email, In-App, SMS, Push) |
| 12 | Point | `point` | 포인트 적립/사용/취소 |
| 13 | Returns | `returns` | 반품/교환 처리 |
| 14 | Seller | `seller` | 판매자 등록, 승인, 수수료율 관리 |
| 15 | Wishlist | `wishlist` | 찜 목록 |
| 16 | RAG | `rag` | AI 기반 Q&A (OpenAI 연동, 벡터 검색) |
| 17 | Game | `game` | 미니게임 (바둑, 오목) |

---

## 1. User 도메인

### 엔티티
- **User**: id, email, passwordHash, role(`UserRole`), createdAt, updatedAt
- **PasswordResetToken**: id, userId, token(UUID), expiryDate, used, createdAt

### Enum
- **UserRole**: `USER`, `ADMIN`, `MANAGER`

### 도메인 예외
- `DuplicateEmailException`, `InvalidCredentialsException`, `InvalidPasswordResetTokenException`, `UserNotFoundException`

### 아웃바운드 포트
- `LoadUserPort` — findByEmail, findById, findAll
- `SaveUserPort` — save
- `ExistsUserPort` — existsByEmail
- `PasswordHashPort` — hash, matches
- `TokenProviderPort` — generateToken, validateToken, getEmailFromToken
- `SavePasswordResetTokenPort` — save, findByToken, findValidTokenByUserId
- `SendEmailPort` — sendPasswordResetEmail

### 어댑터 (out)
- JPA Persistence, BCrypt Password Hash, JWT Token, OAuth2 Social Auth (Google/Kakao/Naver), Email

---

## 2. Order 도메인

### 엔티티
- **Order**: id, userId, productId, amount(`BigDecimal`), status(`OrderStatus`), createdAt, updatedAt

### Enum
- **OrderStatus**: `CREATED` → `PAID` / `CANCELED` / `REFUNDED`

### 도메인 비즈니스 로직
- `cancel()`: CREATED → CANCELED
- `complete()`: CREATED → PAID
- `refund()`: PAID → REFUNDED

### 도메인 예외
- `OrderNotFoundException`, `UserNotExistsException`

### 아웃바운드 포트
- `LoadOrderPort`, `SaveOrderPort`, `LoadUserForOrderPort`, `SendOrderNotificationPort`

---

## 3. Payment 도메인

### 엔티티
- **PaymentDomain**: id, orderId, amount, refundedAmount, status(`PaymentStatus`), paymentMethod, pgTransactionId, capturedAt, createdAt, updatedAt

### Enum
- **PaymentStatus**: `READY` → `AUTHORIZED` → `CAPTURED` → `REFUNDED` / `FAILED` / `CANCELED`

### 도메인 비즈니스 로직
- `authorize(pgTransactionId)`: READY → AUTHORIZED
- `capture()`: AUTHORIZED → CAPTURED
- `refund()`: CAPTURED → REFUNDED
- `getRefundableAmount()`, `isFullyRefunded()`, `addRefundedAmount()`

### 도메인 예외
- `InvalidOrderStateException`, `InvalidPaymentStateException`, `OrderNotFoundException`, `PaymentNotFoundException`

### 아웃바운드 포트
- `LoadPaymentPort`, `SavePaymentPort`, `LoadOrderPort`, `UpdateOrderStatusPort`
- `PgClientPort` — authorize, capture, refund (외부 PG)
- `PublishEventPort` — 이벤트 발행

### Toss Payments 연동
- `TossPaymentConfirmRequest`: paymentKey, tossOrderId, amount
- `TossCartConfirmRequest`: 다건 주문 결제 확인

---

## 4. Settlement 도메인 (핵심)

### 엔티티
- **Settlement**: id, paymentId, orderId, paymentAmount, refundedAmount, commission(3%), netAmount, status(`SettlementStatus`), settlementDate, failureReason, confirmedAt, createdAt, updatedAt

### Enum (11개 상태)
- **SettlementStatus**: `REQUESTED`, `PROCESSING`, `DONE`, `FAILED`, `PENDING`, `WAITING_APPROVAL`, `APPROVED`, `REJECTED`, `CONFIRMED`, `CANCELED`, `CALCULATED`

### 도메인 비즈니스 로직 (상태 머신)
| 메서드 | 전이 |
|--------|------|
| `startProcessing()` | REQUESTED → PROCESSING |
| `complete()` | PROCESSING → DONE |
| `fail(reason)` | PROCESSING → FAILED |
| `retry()` | FAILED → REQUESTED |
| `confirm()` | PENDING/WAITING_APPROVAL/PROCESSING → CONFIRMED |
| `cancel()` | (비확정 상태) → CANCELED |
| `adjustForRefund(amount)` | 환불 금액 반영, netAmount 재계산, netAmount ≤ 0 이면 자동 취소 |

### 수수료 계산
- **수수료율**: 3% 고정
- **정산금 계산**: `netAmount = paymentAmount - refundedAmount - commission`

### CQRS 패턴
- **Write Path**: `LoadSettlementPort` / `SaveSettlementPort` (JPA)
- **Read Path**: `QuerySettlementPort` → `SettlementQueryRepositoryImpl` (QueryDSL 커서 기반 페이지네이션)

### 배치 처리
- **일일 정산 생성**: 매일 02:00 (`@Scheduled cron`)
- **일일 정산 확정**: 매일 03:00 (`@Scheduled cron`)
- **Spring Batch Job**: `createSettlementJob`, `confirmSettlementJob`

### Elasticsearch 연동
- `SettlementSearchAdapter` (실제) / `NoOpSettlementSearchAdapter` (Fallback)
- `SettlementSearchDocument` 인덱싱
- 실패 시 `SettlementIndexQueueAdapter`로 재시도 큐잉

### 아웃바운드 포트
- `LoadSettlementPort`, `SaveSettlementPort`, `LoadCapturedPaymentsPort`
- `QuerySettlementPort` — 일별/월별 요약, 검색, 대사, 감사 추적
- `PublishSettlementEventPort`, `SettlementSearchIndexPort`, `EnqueueFailedIndexPort`
- `SettlementPdfPort` — iText 8 PDF 렌더링

---

## 5. Product 도메인

### 엔티티
- **Product**: id, name, description, price, stockQuantity, status(`ProductStatus`), categoryId, tagIds, createdAt, updatedAt
- **ProductImage**: id, productId, originalFileName, url, isPrimary, orderIndex (소프트 삭제)
- **StockReservation**: id, userId, productId, quantity, status, expiresAt

### Enum
- **ProductStatus**: `ACTIVE`, `INACTIVE`, `OUT_OF_STOCK`, `DISCONTINUED`

### 하위 도메인
- **Category** (상품 카테고리): id, name, parentId, displayOrder, isActive
- **Tag**: id, name, color(HEX)
- **ProductImage**: 다건 이미지 관리, 대표 이미지 설정, 정렬
- **StockReservation**: 재고 예약, 만료 자동 해제 (`@Scheduled` 60초)

### Elasticsearch 연동
- `ProductSearchAdapter` / `NoOpProductSearchAdapter`

---

## 6. Category 도메인 (이커머스)

### 엔티티
- **EcommerceCategory**: id, name, slug, parentId, depth(0~2, 최대 3단계), sortOrder, isActive, deletedAt(소프트 삭제), children(List)

### 도메인 예외
- `CategoryDepthExceededException`, `CategoryHasChildrenException`, `CircularReferenceException`, `DuplicateSlugException`

---

## 7. Coupon 도메인

### 엔티티
- **Coupon**: id, code, type(`CouponType`), discountValue, minOrderAmount, maxDiscountAmount, maxUses, usedCount, expiresAt, isActive

### Enum
- **CouponType**: `FIXED` (정액), `PERCENTAGE` (정률)

### 도메인 비즈니스 로직
- `validate(orderAmount)`: 활성/만료/사용횟수/최소주문금액 검증
- `calculateDiscount(orderAmount)`: FIXED → 할인금, PERCENTAGE → floor(금액×할인율/100)
- `calculateDiscountForRefund()`: 환불 시 비례 할인 계산
- **1인 1회 사용 제한**: `coupon_usages` 테이블 (couponId + userId UNIQUE)

---

## 8. Review 도메인

### 엔티티
- **Review**: id, productId, userId, rating(1~5), content, createdAt, updatedAt
- **제약**: 1인 1상품 1리뷰 (UNIQUE: user_id + product_id)

---

## 9. Cart 도메인

### 엔티티
- **Cart**: id, userId, status(`CartStatus`), items(List), createdAt, updatedAt
- **CartItem**: id, cartId, productId, quantity, priceSnapshot

### Enum
- **CartStatus**: `ACTIVE`, `CHECKED_OUT`, `ABANDONED`

### 도메인 비즈니스 로직
- `addItem()`: 기존 상품이면 수량 병합, 없으면 추가
- `removeItem()`, `updateItemQuantity()`, `clearItems()`, `checkout()`
- `getTotalAmount()`, `getTotalItemCount()`

---

## 10. Shipping 도메인

### 엔티티
- **Delivery**: id, orderId, addressId, status(`DeliveryStatus`), trackingNumber, carrier, shippingFee
- **ShippingAddress**: id, userId, recipientName, phone, zipCode, address, isDefault

### Enum
- **DeliveryStatus**: `PREPARING` → `SHIPPED` → `IN_TRANSIT` → `OUT_FOR_DELIVERY` → `DELIVERED` / `CANCELED`

### 도메인 비즈니스 로직
- 배송비 계산: 주문금액 ≥ 50,000원 → 무료, 미만 → 3,000원

---

## 11. Notification 도메인

### 엔티티
- **Notification**: id, userId, type, channel, title, content, status, referenceType, referenceId

### Enum
- **NotificationType**: ORDER_CREATED, ORDER_PAID, PAYMENT_COMPLETED, DELIVERY_SHIPPED 등 11종
- **NotificationChannel**: `EMAIL`, `IN_APP`, `SMS`, `PUSH`
- **NotificationStatus**: `PENDING`, `SENT`, `FAILED`, `READ`

---

## 12. Point 도메인

### 엔티티
- **Point**: id, userId, balance, totalEarned, totalUsed
- **PointTransaction**: id, userId, type, amount, balanceAfter, description, referenceType, referenceId

### Enum
- **PointTransactionType**: `EARN`, `USE`, `CANCEL_EARN`, `CANCEL_USE`, `EXPIRE`, `ADMIN_ADJUST`

---

## 13. Returns 도메인

### 엔티티
- **ReturnOrder**: id, orderId, userId, type(`ReturnType`), status(`ReturnStatus`), reason(`ReturnReason`), refundAmount

### Enum
- **ReturnType**: `RETURN`, `EXCHANGE`
- **ReturnStatus**: `REQUESTED` → `APPROVED` → `SHIPPED` → `RECEIVED` → `COMPLETED` / `REJECTED` / `CANCELED`
- **ReturnReason**: `DEFECTIVE`, `WRONG_ITEM`, `CHANGED_MIND`, `SIZE_ISSUE` 등 7종

---

## 14. Seller 도메인

### 엔티티
- **Seller**: id, userId, businessName, businessNumber, representativeName, phone, email, bankName, bankAccountNumber, commissionRate, status(`SellerStatus`)

### Enum
- **SellerStatus**: `PENDING`, `APPROVED`, `SUSPENDED`, `REJECTED`

---

## 15. Wishlist 도메인

### 엔티티
- **WishlistItem**: id, userId, productId, createdAt

---

## 16. RAG 도메인

### 엔티티
- **Conversation**: sessionId, messages(List)
- **DocumentChunk**: id, entityType(`EntityType`), entityId, content, embedding(float[]), similarity

### Enum
- **EntityType**: `PRODUCT`, `REVIEW`, `ORDER`, `SETTLEMENT`

### 외부 연동
- OpenAI Chat API (스트리밍), OpenAI Embedding API, 벡터 검색

---

## 17. Game 도메인

- 미니게임: 바둑, 오목 (HTML 템플릿 서빙)

---

## 도메인 간 관계도

```
User ──┬── Order ──── Payment ──── Settlement
       │      │           │
       │      │           └── Refund ──── Settlement Adjustment
       │      │
       │      ├── Delivery (Shipping)
       │      ├── ReturnOrder (Returns)
       │      └── Coupon Usage
       │
       ├── Cart ──── CartItem ──── Product
       ├── Review ──── Product
       ├── Wishlist ──── Product
       ├── Point
       ├── Notification
       └── ShippingAddress

Product ──┬── Category
          ├── Tag
          ├── ProductImage
          └── StockReservation

Seller ──── User (1:1)

RAG ──── Product, Review, Order, Settlement (벡터 임베딩)
```

---

## 데이터베이스 스키마 (Flyway V1~V22)

| 테이블 | 주요 컬럼 | 관계 |
|--------|-----------|------|
| `users` | id, email, password, role, status | - |
| `orders` | id, user_id, product_id, amount, status | → users |
| `payments` | id, order_id, amount, refunded_amount, status, pg_transaction_id | → orders |
| `settlements` | id, payment_id, order_id, payment_amount, commission, net_amount, status, settlement_date | → payments, orders |
| `refunds` | id, payment_id, amount, status, reason, idempotency_key | → payments |
| `settlement_adjustments` | id, settlement_id, refund_id, amount(음수), status | → settlements, refunds |
| `products` | id, name, price, stock_quantity, status, category_id | - |
| `product_images` | id, product_id, url, is_primary, order_index | → products |
| `stock_reservations` | id, product_id, user_id, quantity, expires_at | → products |
| `categories` | id, name, parent_id, display_order, is_active | self-ref |
| `ecommerce_categories` | id, name, slug, parent_id, depth, sort_order | self-ref |
| `tags` | id, name, color | - |
| `product_tags` | product_id, tag_id | → products, tags |
| `reviews` | id, product_id, user_id, rating, content | → products, users |
| `coupons` | id, code, type, discount_value, max_uses, used_count | - |
| `coupon_usages` | coupon_id, user_id, order_id | → coupons, users |
| `password_reset_tokens` | id, user_id, token, expiry_date, used | → users |
| `settlement_index_queue` | id, settlement_id, operation, status, retry_count | - |
| `settlement_schedule_config` | id, schedule_time, enabled | - |