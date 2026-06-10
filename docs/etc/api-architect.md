# API 설계 원칙 및 프로젝트 검토

> 작성일: 2026-03-17
> 대상 프로젝트: `kubenetis/settlement`, `msa/example-order-master`, `msa/example-gift-master`

---

## 목차

1. [자원 중심의 URI 설계](#1-자원-중심의-uri-설계)
2. [HTTP 메서드 활용](#2-http-메서드-활용)
3. [행동 기반 API 설계 시 주의점](#3-행동-기반-api-설계-시-주의점)
4. [필터링·정렬·페이지네이션](#4-필터링정렬페이지네이션)
5. [에러 핸들링](#5-에러-핸들링)
6. [프로젝트 검토 결과](#6-프로젝트-검토-결과)

---

## 1. 자원 중심의 URI 설계

URI는 **명사(자원)** 만 표현하고, **동사(행위)** 는 HTTP 메서드로 표현한다.

```
❌ 동사 기반                   ✅ 자원 기반
/getOrder/1              →   GET    /orders/1
/createOrder             →   POST   /orders
/deleteUser/5            →   DELETE /users/5
/getUserOrders/3         →   GET    /users/3/orders
/updateOrderStatus/1     →   PATCH  /orders/1
```

### 계층 구조 (Sub-Resource)

소유 관계가 명확할 때 계층으로 표현한다.

```
GET  /users/{userId}/orders           유저의 주문 목록
GET  /orders/{orderId}/items          주문의 아이템 목록
GET  /payments/{paymentId}/settlement 결제의 정산 내역
```

**3단계 이상은 피한다.** 가독성과 유지보수가 어려워진다.

```
❌ /users/1/orders/5/items/3/options   너무 깊음
✅ /order-items/3/options              독립 리소스로 분리
```

### Path Variable vs Query Parameter 구분 기준

| 사용 목적 | 방법 | 예시 |
|-----------|------|------|
| 특정 자원 식별 | Path Variable | `/orders/{id}` |
| 필터링·검색·조건 | Query Parameter | `/orders?status=PAID` |
| 정렬 | Query Parameter | `/orders?sort=createdAt,desc` |
| 페이지네이션 | Query Parameter | `/orders?page=0&size=20` |

---

## 2. HTTP 메서드 활용

| 메서드 | 의미 | 멱등성 | 성공 응답 코드 |
|--------|------|--------|----------------|
| `GET` | 조회 | ✅ | 200 |
| `POST` | 생성 | ❌ | **201** + Location 헤더 권장 |
| `PUT` | 전체 교체 | ✅ | 200 또는 204 |
| `PATCH` | 부분 수정 / 상태 변경 | ❌ | 200 또는 204 |
| `DELETE` | 삭제 | ✅ | **204** No Content |

> **멱등성**: 동일한 요청을 여러 번 보내도 결과가 같음.
> 네트워크 장애로 요청이 중복 전송될 때 안전하게 재시도 가능.

### 주요 응답 코드

```
2xx 성공
  200 OK           일반 성공 (조회, 수정)
  201 Created      자원 생성 성공 (POST)
  204 No Content   성공, 반환 본문 없음 (DELETE, 일부 PATCH)

4xx 클라이언트 오류
  400 Bad Request        요청 형식/값 오류, 유효성 검증 실패
  401 Unauthorized       인증 필요 (토큰 없음/만료)
  403 Forbidden          인증은 됐지만 권한 없음
  404 Not Found          자원 없음
  409 Conflict           상태 충돌 (이미 취소된 주문에 결제 시도)
  422 Unprocessable      형식은 맞지만 비즈니스 규칙 위반

5xx 서버 오류
  500 Internal Server Error   예상치 못한 시스템 오류
```

---

## 3. 행동 기반 API 설계 시 주의점

비즈니스는 항상 "행동"을 포함한다. `cancel`, `approve`, `capture` 같은 동사를 어떻게 표현할까?

### 방법 1 — 상태 전이를 sub-resource 액션으로 표현 (권장)

```
PATCH /orders/{id}/cancel
PATCH /payments/{id}/capture
PATCH /payments/{id}/refund
PATCH /categories/{id}/activate
PATCH /categories/{id}/deactivate
```

상태 변경 행위를 하위 리소스로 표현. 가장 널리 쓰이는 방법.

### 방법 2 — 행위를 명사 리소스로 모델링

```
POST /orders/{id}/cancellations    취소 이벤트 생성 (이력 남김)
POST /orders/{id}/payments         결제 생성
POST /coupons/{code}/usages        쿠폰 사용 기록 생성
```

행동을 "명사화"하여 리소스처럼 표현. 이력 추적이 필요할 때 적합.

### 방법 3 — RPC-style (불가피한 경우만)

순수 REST로 표현하기 어려운 복잡한 액션에 한해 허용.

```
POST /payments/confirm        외부 PG 콜백 확인
POST /settlements/batch-run   배치 수동 실행
```

**규칙: 반드시 `POST`를 사용하고, 경로 마지막에만 동사를 쓴다.**

### 주의점 — URL에 드러내지 말아야 할 것들

```
❌ 구현 기술 노출        /payments/toss/confirm    → PG사명
❌ 역할 노출             /orders/admin/all         → 역할
❌ 내부 로직 노출        /orders/init              → 초기화 단계
❌ 중복 컨텍스트         /gifts/{token}/accept-gift → -gift 중복

✅ 비즈니스 행위만        /payments/confirm
✅ 역할은 Spring Security  @PreAuthorize("hasRole('ADMIN')")
✅ 자원 생성              POST /orders
✅ 중복 제거              /gifts/{token}/accept
```

---

## 4. 필터링·정렬·페이지네이션

모든 목록 조회 API에 기본으로 적용해야 한다.

### 필터링

```
GET /orders?userId=1&status=PAID
GET /settlements?from=2025-01-01&to=2025-01-31&status=DONE
GET /coupons?type=PERCENTAGE&active=true
```

### 정렬

```
GET /orders?sort=createdAt,desc
GET /settlements?sort=amount,asc&sort=createdAt,desc    다중 정렬
```

### 페이지네이션

```
GET /orders?page=0&size=20
```

### Spring 구현 패턴

```java
@GetMapping
public ResponseEntity<Page<OrderResponse>> getOrders(
        @RequestParam(required = false) OrderStatus status,
        @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate to,
        @PageableDefault(size = 20, sort = "createdAt", direction = DESC) Pageable pageable
) {
    return ResponseEntity.ok(orderService.getOrders(status, from, to, pageable));
}
```

### 페이지네이션 응답 구조

```json
{
  "content": [...],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8,
  "last": false
}
```

---

## 5. 에러 핸들링

### GlobalExceptionHandler 구조 (`@RestControllerAdvice`)

`example-order`, `example-gift` 프로젝트에 적용한 패턴.

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 400 - 유효성 검증 실패 (MethodArgumentNotValidException)
    // 400 - 비즈니스 파라미터 오류 (InvalidParamException)
    // 404 - 엔티티 없음 (EntityNotFoundException)
    // 409 - 상태 충돌 (IllegalStatusException)
    // 400 - 기타 비즈니스 예외 (BaseException)
    // 500 - 시스템 예외 폴백 (Exception)
}
```

### 예외 계층 → HTTP 상태 코드 매핑

| 예외 클래스 | HTTP 상태 | 이유 |
|-------------|-----------|------|
| `MethodArgumentNotValidException` | 400 | 입력 형식/값 오류 |
| `InvalidParamException` | 400 | 비즈니스 파라미터 오류 |
| `EntityNotFoundException` | 404 | 존재하지 않는 자원 |
| `IllegalStatusException` | 409 | 상태 전이 불가 충돌 |
| `BaseException` | 400 | 기타 예상 가능한 비즈니스 예외 |
| `Exception` | 500 | 예상치 못한 시스템 예외 |

### 일관된 에러 응답 구조

```json
{
  "result": "FAIL",
  "data": null,
  "message": "존재하지 않는 엔티티입니다.",
  "errorCode": "COMMON_ENTITY_NOT_FOUND"
}
```

### 주의사항

- `@ControllerAdvice` + `@ResponseBody` 대신 `@RestControllerAdvice` 사용 (간결)
- `@ResponseStatus` 어노테이션 대신 `ResponseEntity`로 상태 코드 반환 (명시적)
- 하위 예외 핸들러를 상위보다 먼저 선언 (`EntityNotFoundException` → `BaseException` 순)

---

## 6. 프로젝트 검토 결과

### 6-1. settlement 프로젝트

#### 잘 된 것

| 컨트롤러 | 잘 된 점 |
|----------|---------|
| `AdminEcommerceCategoryController` | `PATCH /{id}/activate`, `PATCH /{id}/deactivate` — 상태 전이 패턴 모범적 |
| `AdminEcommerceCategoryController` | `DELETE → 204 No Content`, `POST → 201 Created` 상태 코드 정확 |
| `AdminEcommerceCategoryController` | `@PreAuthorize("hasRole('ADMIN')")` — 역할을 URL이 아닌 코드로 제어 |
| `PaymentController` | `PATCH /{id}/authorize`, `capture`, `refund` — 결제 상태 전이 명확 |
| `CouponController` | `GET /{code}/validate?userId=&amount=` — 필터를 쿼리 파라미터로 정확히 표현 |

#### 개선 필요

| 컨트롤러 | 문제 | 개선 방향 |
|----------|------|-----------|
| `OrderController` | `GET /orders/user/{userId}` | `GET /users/{userId}/orders` 또는 `GET /orders?userId=` |
| `OrderController` | `GET /orders/admin/all` | 컨트롤러 분리 + `@PreAuthorize`, URL은 `GET /orders` |
| `SettlementController` | `GET /settlements/payment/{paymentId}` | `GET /settlements?paymentId=` |
| `SettlementController` | 목록 조회·필터링·페이지네이션 없음 | `GET /settlements?from=&to=&status=&page=&size=` |
| `PaymentController` | `POST /payments/toss/confirm` — PG사명 URL 노출 | `POST /payments/confirm` |
| `CouponController` | `POST /{code}/use` → 200 반환 | 반환값 없으면 `204 No Content` |
| `GlobalExceptionHandler` | 결제 예외 4개만 처리 | 도메인 전체 예외 + `Exception` 폴백 추가 |
| `GlobalExceptionHandler` | `Map<String, Object>` 응답 | 공통 에러 응답 클래스 사용 |

---

### 6-2. example-order 프로젝트 (수정 완료)

#### 수정 내용

| 수정 전 | 수정 후 | 분류 |
|---------|---------|------|
| `POST /orders/init` | `POST /orders` → 201 | URI 동사 제거 + 상태 코드 |
| `POST /orders/payment-order` | `POST /orders/{orderToken}/payment` | URI 동사 제거 + sub-resource |
| `POST /items/change-on-sales` (body에 token) | `PATCH /items/{itemToken}/on-sale` | 메서드 + URI 수정 |
| `POST /items/change-end-of-sales` (body에 token) | `PATCH /items/{itemToken}/end-of-sale` | 메서드 + URI 수정 |
| `POST /items` → 200 | `POST /items` → 201 | 상태 코드 |
| `POST /partners` (`toCommand()` 직접 호출) | `PartnerDtoMapper` 분리 + 201 | 일관성 + 상태 코드 |
| `CommonControllerAdvice` | `GlobalExceptionHandler` | 예외 핸들링 구조화 |
| `EntityNotFoundException` → 400 | `EntityNotFoundException` → 404 | 올바른 ErrorCode 매핑 |

---

### 6-3. example-gift 프로젝트 (수정 완료)

#### 수정 내용

| 수정 전 | 수정 후 | 분류 |
|---------|---------|------|
| `POST /gifts/{token}/accept-gift` | `POST /gifts/{token}/accept` | URI 중복 제거 |
| `POST /gifts/{token}/payment-processing` | `POST /gifts/{token}/payment` | URI 동사/명사 혼재 정리 |
| `POST /gifts` → 200 | `POST /gifts` → 201 | 상태 코드 |
| `CommonControllerAdvice` | `GlobalExceptionHandler` | 예외 핸들링 구조화 |
| `EntityNotFoundException` → 400 | `EntityNotFoundException` → 404 | 올바른 ErrorCode 매핑 |

---

### 6-4. 세 프로젝트 공통 평가표

| 항목 | settlement | example-order | example-gift |
|------|:----------:|:-------------:|:------------:|
| 자원 중심 URI | ⚠️ 일부 동사 | ✅ 수정 완료 | ✅ 수정 완료 |
| HTTP 메서드 적절성 | ✅ | ✅ | ✅ |
| 생성 시 201 반환 | ✅ | ✅ | ✅ |
| 삭제 시 204 반환 | ✅ | - | - |
| 행동 기반 API (상태 전이) | ✅ 우수 | ✅ | ✅ |
| 역할 기반 접근 제어 | ✅ Category | ❌ 없음 | ❌ 없음 |
| 필터링 (Query Param) | ⚠️ 쿠폰만 | ❌ 없음 | ❌ 없음 |
| 페이지네이션 | ❌ 없음 | ❌ 없음 | ❌ 없음 |
| 정렬 | ❌ 없음 | ❌ 없음 | ❌ 없음 |
| GlobalExceptionHandler | ⚠️ 일부만 | ✅ 수정 완료 | ✅ 수정 완료 |

> **세 프로젝트 공통 개선 과제**: 목록 조회 API에 필터링·정렬·페이지네이션 적용