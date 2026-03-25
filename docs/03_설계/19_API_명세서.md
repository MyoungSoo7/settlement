# API 명세서

| 항목 | 내용 |
|------|------|
| 문서번호 | LML-API-001 |
| 버전 | 1.1 |
| 작성일 | 2026-03-25 |
| 작성자 | 개발PL |
| 승인자 | TA |
| 문서상태 | 개선 |
| 기밀등급 | 대외비 |

---

## 버전 이력

| 버전 | 작성일 | 작성자 | 변경 내용 |
|------|--------|--------|-----------|
| 0.1 | 2026-03-25 | 개발PL | 초안 작성 |
| 1.0 | 2026-03-25 | 개발PL | 최종 확정 |
| 1.1 | 2026-03-25 | 개발PL | 실제 소스코드 기반 엔드포인트 반영 |

---

## 1. API 개요

### 1.1 공통 사항

| 항목 | 내용 |
|------|------|
| Base URL | 도메인별 상이 (아래 참조) |
| 프로토콜 | HTTPS |
| 인증 | JWT Bearer Token (jjwt 0.12.5) |
| Content-Type | application/json |
| 문자셋 | UTF-8 |
| API 문서 | SpringDoc OpenAPI 2.8.0 (`/swagger-ui/index.html`) |

### 1.2 인증 헤더
```
Authorization: Bearer {access_token}
```

### 1.3 공통 응답 코드

| HTTP 코드 | 설명 |
|-----------|------|
| 200 | 성공 |
| 201 | 생성 성공 |
| 400 | 잘못된 요청 |
| 401 | 인증 실패 |
| 403 | 권한 부족 |
| 404 | 리소스 없음 |
| 409 | 충돌 (중복) |
| 500 | 서버 오류 |

### 1.4 Swagger UI
- 로컬: `http://localhost:8088/swagger-ui/index.html`
- Root 엔드포인트 (`GET /`): `{status: "ok", docs: "/swagger-ui/index.html"}`

---

## 2. 인증 API (AuthController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/auth/login` | POST | 불필요 | 로그인 |

### 2.1 POST /auth/login — 로그인

**Request Body:**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| email | String | Y | 이메일 |
| password | String | Y | 비밀번호 |

**Response (200):**

| 필드 | 타입 | 설명 |
|------|------|------|
| accessToken | String | JWT Access Token |
| tokenType | String | "Bearer" |

---

## 3. 사용자 API (UserController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/users` | POST | 불필요 | 회원가입 |
| `/users/{id}` | GET | 필요 | 사용자 조회 |
| `/users/profile` | GET | 필요 | 내 프로필 조회 |
| `/users/{id}` | PUT | 필요 | 사용자 정보 수정 |

### 3.1 POST /users — 회원가입

**구현**: CreateUserService (implements CreateUserUseCase)

**Request Body:**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| email | String | Y | 이메일 (UK) |
| password | String | Y | 비밀번호 (BCrypt 해싱) |
| name | String | Y | 이름 |

**역할 코드 (UserRole enum):**

| 코드 | 설명 |
|------|------|
| USER | 일반 사용자 |
| ADMIN | 관리자 |
| MANAGER | 매니저 |

---

## 4. 상품 API (ProductController)

**Base Path**: `/api/products`

| 엔드포인트 | 메서드 | 인증 | 설명 | 구현 |
|-----------|--------|------|------|------|
| `/api/products` | POST | ADMIN/MANAGER | 상품 등록 | CreateProductService |
| `/api/products/{productId}` | GET | 불필요 | 상품 상세 조회 | GetProductService |
| `/api/products` | GET | 불필요 | 전체 상품 목록 | GetProductService |
| `/api/products/status/{status}` | GET | 불필요 | 상태별 상품 목록 | GetProductService |
| `/api/products/available` | GET | 불필요 | 판매 가능 상품 | GetProductService |
| `/api/products/{productId}/info` | PUT | ADMIN/MANAGER | 상품 정보 수정 | UpdateProductService |
| `/api/products/{productId}/price` | PUT | ADMIN/MANAGER | 가격 수정 | UpdateProductService |
| `/api/products/{productId}/stock` | PUT | ADMIN/MANAGER | 재고 수정 | UpdateProductService |
| `/api/products/{productId}` | DELETE | ADMIN | 상품 삭제 | - |

### 4.1 POST /api/products — 상품 등록

**Request Body (ProductCreateRequest):**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| name | String | Y | 상품명 (중복 불가) |
| description | String | N | 상품 설명 |
| price | Long | Y | 가격 (≥ 0) |
| stock | Integer | Y | 재고 수량 (≥ 0) |
| categoryId | Long | N | 카테고리 ID |

**상품 상태 (ProductStatus enum):**

| 코드 | 설명 |
|------|------|
| ACTIVE | 판매 중 |
| INACTIVE | 비활성 |
| OUT_OF_STOCK | 품절 |
| DISCONTINUED | 판매 중단 |

---

## 5. 상품 이미지 API (ProductImageController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/product-images` | POST | ADMIN/MANAGER | 이미지 업로드 (multipart/form-data) |
| `/product-images/{productId}` | GET | 불필요 | 상품 이미지 목록 |
| `/product-images/{imageId}` | DELETE | ADMIN/MANAGER | 이미지 삭제 |

---

## 6. 카테고리 API

### 6.1 기본 카테고리 (CategoryController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/categories` | POST | ADMIN | 카테고리 등록 |
| `/categories` | GET | 불필요 | 전체 카테고리 조회 |
| `/categories/{categoryId}` | PUT | ADMIN | 카테고리 수정 |
| `/categories/{categoryId}` | DELETE | ADMIN | 카테고리 삭제 |

### 6.2 이커머스 카테고리 (다계층)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/admin/ecommerce-categories` | POST | ADMIN | 다계층 카테고리 등록 |
| `/ecommerce-categories` | GET | 불필요 | 전체 카테고리 트리 |
| `/admin/ecommerce-categories/{id}` | PUT | ADMIN | 카테고리 수정 |
| `/admin/ecommerce-categories/{id}` | DELETE | ADMIN | 카테고리 삭제 |

---

## 7. 태그 API (TagController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/tags` | POST | ADMIN/MANAGER | 태그 생성 |
| `/tags` | GET | 불필요 | 전체 태그 조회 |
| `/tags/{tagId}` | DELETE | ADMIN | 태그 삭제 |

---

## 8. 주문 API (OrderController)

**Base Path**: `/orders`

| 엔드포인트 | 메서드 | 인증 | 설명 | 구현 |
|-----------|--------|------|------|------|
| `/orders` | POST | USER | 주문 생성 | CreateOrderService |
| `/orders/{id}` | GET | USER | 주문 상세 | GetOrderService |
| `/orders/user/{userId}` | GET | USER | 사용자별 주문 | GetOrderService |
| `/orders/admin/all` | GET | ADMIN | 전체 주문 | GetOrderService |
| `/orders/{id}/cancel` | PATCH | USER | 주문 취소 | ChangeOrderStatusService |

**주문 상태 (OrderStatus enum):**

| 코드 | 설명 | 전이 |
|------|------|------|
| CREATED | 주문 생성 | → CONFIRMED, CANCELLED |
| CONFIRMED | 주문 확정 | → REFUNDED |
| CANCELLED | 주문 취소 | 최종 |
| REFUNDED | 환불 완료 | 최종 |

---

## 9. 결제 API (PaymentController)

**Base Path**: `/payments`

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/payments` | POST | USER | 결제 생성 (READY) |
| `/payments/{id}` | GET | USER | 결제 조회 |
| `/payments/{id}/authorize` | PATCH | USER | 결제 승인 (AUTHORIZED) |
| `/payments/{id}/capture` | PATCH | USER | 결제 매입 (CAPTURED) |
| `/payments/{id}/refund` | PATCH | USER | 환불 요청 |
| `/payments/toss/confirm` | POST | USER | Toss 결제 확인 |
| `/payments/toss/cart/confirm` | POST | USER | Toss 장바구니 결제 |

### 9.1 POST /payments/toss/confirm — Toss 결제 확인

**Request Body (TossConfirmRequest):**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| paymentKey | String | Y | Toss 결제키 |
| orderId | String | Y | 주문 ID |
| amount | Long | Y | 결제 금액 |

### 9.2 PATCH /payments/{id}/refund — 환불

**Request Header:**

| 헤더 | 필수 | 설명 |
|------|------|------|
| Idempotency-Key | Y | 멱등성 키 (UUID) |

**결제 상태 (PaymentStatus enum):**

| 코드 | 설명 | 전이 |
|------|------|------|
| READY | 결제 준비 | → AUTHORIZED, CANCELED |
| AUTHORIZED | 승인 완료 | → CAPTURED, CANCELED |
| CAPTURED | 매입 완료 | → REFUNDED, PARTIALLY_REFUNDED |
| REFUNDED | 전체 환불 | 최종 |
| PARTIALLY_REFUNDED | 부분 환불 | → REFUNDED |
| CANCELED | 취소 | 최종 |

---

## 10. 정산 API (SettlementController, SettlementSearchController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/settlements/{id}` | GET | ADMIN/MANAGER | 정산 상세 |
| `/settlements/payment/{paymentId}` | GET | ADMIN/MANAGER | 결제별 정산 |
| `/settlements/{id}/pdf` | GET | ADMIN/MANAGER | PDF 다운로드 (iText8) |
| `/settlements/search` | POST | ADMIN/MANAGER | ES 검색 |

### 10.1 POST /settlements/search

**Request Body (SettlementSearchRequest):**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| keyword | String | N | 검색어 |
| status | String | N | 정산 상태 |
| startDate | String | N | 시작일 |
| endDate | String | N | 종료일 |
| page | Integer | N | 페이지 |
| size | Integer | N | 크기 |

**정산 상태 (SettlementStatus enum):**

| 코드 | 설명 |
|------|------|
| REQUESTED | 요청됨 |
| PENDING | 대기 |
| CONFIRMED | 확정 |
| FAILED | 실패 (failureReason) |
| CANCELED | 취소 |

---

## 11. 리뷰 API (ReviewController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/reviews` | POST | USER | 리뷰 작성 |
| `/reviews/product/{productId}` | GET | 불필요 | 상품별 리뷰 |
| `/reviews/user/{userId}` | GET | USER | 사용자별 리뷰 |
| `/reviews/{id}` | PUT | USER | 리뷰 수정 |
| `/reviews/{id}` | DELETE | USER | 리뷰 삭제 |

---

## 12. 쿠폰 API (CouponController)

| 엔드포인트 | 메서드 | 인증 | 설명 |
|-----------|--------|------|------|
| `/coupons` | POST | ADMIN | 쿠폰 생성 |
| `/coupons` | GET | ADMIN | 전체 쿠폰 |
| `/coupons/{code}/validate` | GET | USER | 유효성 검증 |
| `/coupons/{code}/use` | POST | USER | 쿠폰 사용 |

**CouponType**: PERCENTAGE, FIXED_AMOUNT

---

## 13. Actuator / 모니터링

| 엔드포인트 | 설명 |
|-----------|------|
| `/actuator/health` | 헬스 (DB, liveness, readiness) |
| `/actuator/prometheus` | Prometheus 메트릭 |

---

> **본 문서는 Lemuel 전자상거래 주문·결제·정산 통합 시스템의 API 명세서입니다.**
> **Swagger UI**: `/swagger-ui/index.html`
