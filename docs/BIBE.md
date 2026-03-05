# BIBE (Backend Information & Business Entity)

## 📋 시스템 개요
**Lemuel** - 전자상거래 정산 시스템
주문, 결제, 환불, 정산을 관리하는 통합 정산 플랫폼

---

## 🗂️ 도메인 모델

### 1. User (사용자)
**테이블명**: `users`

| 필드 | 타입 | 설명 |
|------|------|------|
| id | BIGINT (PK) | 사용자 ID |
| email | VARCHAR (UNIQUE) | 이메일 (로그인 ID) |
| password | VARCHAR | 암호화된 비밀번호 |
| role | VARCHAR | 권한 (USER, ADMIN) |
| created_at | TIMESTAMP | 생성 시간 |

**비즈니스 규칙**:
- 이메일은 중복 불가
- 기본 role은 'USER'
- 비밀번호는 BCrypt 암호화

---

### 2. Order (주문)
**테이블명**: `orders`

| 필드 | 타입 | 설명 |
|------|------|------|
| id | BIGINT (PK) | 주문 ID |
| user_id | BIGINT | 사용자 ID |
| amount | DECIMAL(10,2) | 주문 금액 |
| status | VARCHAR(20) | 주문 상태 |
| created_at | TIMESTAMP | 생성 시간 |
| updated_at | TIMESTAMP | 수정 시간 |

**주문 상태 (OrderStatus)**:
- `CREATED`: 주문 생성 (결제 전)
- `PAID`: 결제 완료
- `CANCELED`: 결제 전 취소
- `REFUNDED`: 환불 완료

**비즈니스 프로세스**:
1. 사용자가 주문 생성 → `CREATED`
2. 결제 완료 → `PAID`
3. 환불 시 → `REFUNDED`

---

### 3. Payment (결제)
**테이블명**: `payments`

| 필드 | 타입 | 설명 |
|------|------|------|
| id | BIGINT (PK) | 결제 ID |
| order_id | BIGINT | 주문 ID |
| amount | DECIMAL(10,2) | 결제 금액 |
| refunded_amount | DECIMAL(10,2) | 환불된 금액 |
| status | VARCHAR(20) | 결제 상태 |
| payment_method | VARCHAR(50) | 결제 수단 |
| pg_transaction_id | VARCHAR(100) | PG사 거래 ID |
| captured_at | TIMESTAMP | 매입 확정 시간 |
| created_at | TIMESTAMP | 생성 시간 |
| updated_at | TIMESTAMP | 수정 시간 |

**결제 상태 (PaymentStatus)**:
- `READY`: 결제 생성 (승인 전)
- `AUTHORIZED`: 승인됨 (카드 승인)
- `CAPTURED`: 매입 확정 (실제 결제 완료)
- `FAILED`: 결제 실패
- `CANCELED`: 승인 취소
- `REFUNDED`: 환불 완료

**비즈니스 규칙**:
- 환불 가능 금액 = `amount - refunded_amount`
- 전액 환불 시 `refunded_amount >= amount`

**비즈니스 프로세스**:
1. 주문 생성 후 결제 생성 → `READY`
2. PG사 승인 → `AUTHORIZED`
3. 매입 확정 → `CAPTURED` (정산 대상)
4. 환불 시 → `REFUNDED`

---

### 4. Refund (환불)
**테이블명**: `refunds`

| 필드 | 타입 | 설명 |
|------|------|------|
| id | BIGINT (PK) | 환불 ID |
| payment_id | BIGINT | 결제 ID |
| amount | DECIMAL(10,2) | 환불 금액 |
| status | VARCHAR(20) | 환불 상태 |
| reason | TEXT | 환불 사유 |
| idempotency_key | VARCHAR(255) | 멱등성 키 |
| requested_at | TIMESTAMP | 요청 시간 |
| completed_at | TIMESTAMP | 완료 시간 |
| created_at | TIMESTAMP | 생성 시간 |
| updated_at | TIMESTAMP | 수정 시간 |

**환불 상태 (RefundStatus)**:
- `REQUESTED`: 환불 요청
- `APPROVED`: 환불 승인
- `COMPLETED`: 환불 완료
- `FAILED`: 환불 실패
- `CANCELED`: 환불 취소

**비즈니스 규칙**:
- `idempotency_key`로 중복 환불 방지
- 부분 환불 가능 (Payment의 refunded_amount에 누적)

**비즈니스 프로세스**:
1. 환불 요청 → `REQUESTED`
2. 관리자 승인 → `APPROVED`
3. PG사 환불 완료 → `COMPLETED`
4. Payment의 `refunded_amount` 증가
5. Settlement에 조정(Adjustment) 반영

---

### 5. Settlement (정산)
**테이블명**: `settlements`

| 필드 | 타입 | 설명 |
|------|------|------|
| id | BIGINT (PK) | 정산 ID |
| payment_id | BIGINT | 결제 ID |
| order_id | BIGINT | 주문 ID |
| amount | DECIMAL(10,2) | 정산 금액 |
| status | VARCHAR(20) | 정산 상태 |
| settlement_date | DATE | 정산 예정일 |
| confirmed_at | TIMESTAMP | 확정 시간 |
| approved_by | BIGINT | 승인자 ID |
| approved_at | TIMESTAMP | 승인 시간 |
| rejected_by | BIGINT | 반려자 ID |
| rejected_at | TIMESTAMP | 반려 시간 |
| rejection_reason | VARCHAR(500) | 반려 사유 |
| created_at | TIMESTAMP | 생성 시간 |
| updated_at | TIMESTAMP | 수정 시간 |

**정산 상태 (SettlementStatus)**:
- `CALCULATED`: 정산 금액 계산 완료
- `WAITING_APPROVAL`: 승인 대기 중
- `APPROVED`: 승인됨
- `REJECTED`: 반려됨
- `PENDING`: 정산 대상 생성 (하위 호환)
- `CONFIRMED`: 정산 확정 (하위 호환)
- `CANCELED`: 정산 취소

**비즈니스 규칙**:
- Payment가 `CAPTURED` 상태일 때 정산 대상
- 정산일(settlement_date)은 결제일 기준으로 계산
- 승인 프로세스: `WAITING_APPROVAL` → `APPROVED` → `CONFIRMED`

**비즈니스 프로세스**:
1. 결제 매입 완료 후 스케줄러가 정산 생성 → `CALCULATED`
2. 관리자가 검토 → `WAITING_APPROVAL`
3. 관리자 승인 → `APPROVED`
4. 회계 확정 → `CONFIRMED`
5. 환불 발생 시 → Adjustment 생성

---

### 6. SettlementAdjustment (정산 조정)
**테이블명**: `settlement_adjustments`

| 필드 | 타입 | 설명 |
|------|------|------|
| id | BIGINT (PK) | 조정 ID |
| settlement_id | BIGINT | 정산 ID |
| refund_id | BIGINT | 환불 ID |
| amount | DECIMAL(10,2) | 조정 금액 (음수) |
| status | VARCHAR(20) | 조정 상태 |
| adjustment_date | DATE | 조정 예정일 |
| confirmed_at | TIMESTAMP | 확정 시간 |
| created_at | TIMESTAMP | 생성 시간 |
| updated_at | TIMESTAMP | 수정 시간 |

**조정 상태 (AdjustmentStatus)**:
- `PENDING`: 조정 대기
- `CONFIRMED`: 조정 확정

**비즈니스 프로세스**:
1. 환불 발생 → 기존 정산에 대한 조정 생성
2. 조정 금액은 환불 금액의 음수
3. 스케줄러가 자동 확정 처리

---

### 7. SettlementScheduleConfig (정산 스케줄 설정)
**테이블명**: `settlement_schedule_config`

| 필드 | 타입 | 설명 |
|------|------|------|
| id | BIGINT (PK) | 설정 ID |
| config_key | VARCHAR(100) | 설정 키 |
| cron_expression | VARCHAR(100) | Cron 표현식 |
| enabled | BOOLEAN | 활성화 여부 |
| description | VARCHAR(500) | 설명 |
| merchant_id | BIGINT | 업체 ID (null=전체) |
| created_at | TIMESTAMP | 생성 시간 |
| updated_at | TIMESTAMP | 수정 시간 |

**설정 키 (Config Key)**:
- `SETTLEMENT_CREATE`: 정산 생성 스케줄
- `SETTLEMENT_CONFIRM`: 정산 확정 스케줄
- `ADJUSTMENT_CONFIRM`: 조정 확정 스케줄

**비즈니스 규칙**:
- merchant_id가 null이면 전체 적용
- merchant_id가 있으면 특정 업체만 적용

---

### 8. SettlementIndexQueue (정산 인덱스 큐)
**테이블명**: `settlement_index_queue`

Elasticsearch 동기화를 위한 큐 테이블 (검색 최적화)

---

## 🔄 도메인 간 관계 (ERD)

```
┌─────────┐
│  User   │
└────┬────┘
     │ 1:N
     ↓
┌─────────┐
│  Order  │───1:1───→ ┌──────────┐
└────┬────┘           │ Payment  │
     │                └─────┬────┘
     │                      │ 1:N
     │                      ↓
     │                ┌──────────┐
     │                │  Refund  │
     │                └─────┬────┘
     │                      │
     ↓                      ↓
┌────────────┐       ┌────────────────────────┐
│ Settlement │←──────│ SettlementAdjustment  │
└────────────┘       └────────────────────────┘
```

**관계 설명**:
- User → Order: 1:N (한 사용자가 여러 주문 생성)
- Order → Payment: 1:1 (한 주문당 하나의 결제)
- Payment → Refund: 1:N (한 결제에 여러 환불 가능)
- Payment → Settlement: 1:1 (한 결제당 하나의 정산)
- Settlement → SettlementAdjustment: 1:N (한 정산에 여러 조정)
- Refund → SettlementAdjustment: 1:1 (한 환불당 하나의 조정)

---

## 🔌 API 엔드포인트

### 1. Auth API (인증)
**컨트롤러**: `AuthController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/auth/login` | 로그인 (JWT 토큰 발급) |

---

### 2. User API (사용자)
**컨트롤러**: `UserController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/users` | 회원가입 |
| GET | `/users/me` | 현재 사용자 정보 조회 |

---

### 3. Order API (주문)
**컨트롤러**: `OrderController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/orders` | 주문 생성 |
| GET | `/orders/{id}` | 주문 조회 |
| GET | `/orders/user/{userId}` | 사용자별 주문 목록 |

---

### 4. Payment API (결제)
**컨트롤러**: `PaymentController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/payments` | 결제 생성 |
| POST | `/payments/{id}/capture` | 결제 매입 확정 |
| GET | `/payments/{id}` | 결제 조회 |

---

### 5. Refund API (환불)
**컨트롤러**: `RefundController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/payments/{paymentId}/refund` | 환불 요청 |
| GET | `/refunds/{id}` | 환불 조회 |

---

### 6. Settlement API (정산)
**컨트롤러**: `SettlementController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/settlements/{id}/approve` | 정산 승인 |
| POST | `/settlements/{id}/reject` | 정산 반려 |
| GET | `/settlements/{id}` | 정산 상세 조회 |

---

### 7. Settlement Search API (정산 검색)
**컨트롤러**: `SettlementSearchController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/settlements/search` | 정산 검색 (필터, 페이징, 집계) |

**검색 파라미터**:
- `ordererName`: 주문자명
- `productName`: 상품명
- `isRefunded`: 환불 여부
- `status`: 정산 상태
- `startDate`, `endDate`: 기간 필터
- `page`, `size`: 페이징
- `sortBy`, `sortDirection`: 정렬

**응답**:
- 정산 목록
- 페이징 정보
- 집계 정보 (총액, 환불액, 최종액, 상태별 카운트)

---

### 8. Settlement Schedule API (정산 스케줄)
**컨트롤러**: `SettlementScheduleController`

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/settlements/schedule/configs` | 스케줄 설정 목록 조회 |
| PUT | `/settlements/schedule/configs/{id}` | 스케줄 설정 수정 |

---

## 🔄 핵심 비즈니스 프로세스

### 1. 주문 → 결제 → 정산 프로세스

```
[사용자] 주문 생성
    ↓
[Order] status = CREATED
    ↓
[사용자] 결제 요청
    ↓
[Payment] status = READY
    ↓
[PG사] 승인 완료
    ↓
[Payment] status = AUTHORIZED
    ↓
[시스템] 매입 확정
    ↓
[Payment] status = CAPTURED
[Order] status = PAID
    ↓
[스케줄러] 매일 새벽 실행
    ↓
[Settlement] status = CALCULATED
    ↓
[관리자] 검토
    ↓
[Settlement] status = APPROVED
    ↓
[회계팀] 확정
    ↓
[Settlement] status = CONFIRMED
```

---

### 2. 환불 → 정산 조정 프로세스

```
[사용자] 환불 요청
    ↓
[Refund] status = REQUESTED
    ↓
[관리자] 승인
    ↓
[Refund] status = APPROVED
    ↓
[PG사] 환불 처리
    ↓
[Refund] status = COMPLETED
[Payment] refunded_amount += 환불금액
    ↓
[시스템] 기존 Settlement 조회
    ↓
[SettlementAdjustment] 생성
    - amount = -환불금액
    - status = PENDING
    ↓
[스케줄러] 조정 확정
    ↓
[SettlementAdjustment] status = CONFIRMED
```

---

### 3. 정산 승인 프로세스

```
[정산 생성]
Settlement status = WAITING_APPROVAL
    ↓
[관리자] 검토 → 승인
    ↓
Settlement.status = APPROVED
Settlement.approved_by = 관리자 ID
Settlement.approved_at = 현재 시간
    ↓
[관리자] 검토 → 반려
    ↓
Settlement.status = REJECTED
Settlement.rejected_by = 관리자 ID
Settlement.rejected_at = 현재 시간
Settlement.rejection_reason = 반려 사유
```

---

## 🔍 검색 및 집계

### Elasticsearch 연동
- **인덱스**: `settlements`
- **동기화**: SettlementIndexQueue를 통한 비동기 처리
- **검색 기능**:
  - 주문자명, 상품명 검색
  - 환불 여부 필터
  - 정산 상태 필터
  - 기간별 필터
  - 페이징 및 정렬

### 집계 기능
- 총 정산 금액 (totalAmount)
- 총 환불 금액 (totalRefundedAmount)
- 최종 정산 금액 (totalFinalAmount)
- 상태별 카운트 (statusCounts)

---

## 🔐 보안

### 인증 및 인가
- **인증 방식**: JWT (JSON Web Token)
- **권한 관리**: Role 기반 (USER, ADMIN)
- **API 보호**: Spring Security + JWT Filter

### 권한별 접근 제어
- `USER`: 자신의 주문/결제/환불만 조회 가능
- `ADMIN`: 정산 승인/반려, 전체 데이터 조회 가능

---

## 📊 스케줄러

### 정산 생성 스케줄
- **실행 주기**: 매일 새벽 2시 (설정 가능)
- **대상**: `CAPTURED` 상태의 Payment
- **동작**: Settlement 생성 (status = CALCULATED)

### 정산 확정 스케줄
- **실행 주기**: 설정에 따라 실행
- **대상**: `APPROVED` 상태의 Settlement
- **동작**: status를 CONFIRMED로 변경

### 조정 확정 스케줄
- **실행 주기**: 설정에 따라 실행
- **대상**: `PENDING` 상태의 SettlementAdjustment
- **동작**: status를 CONFIRMED로 변경

---

## 🗄️ 데이터베이스 ERD

```sql
users (사용자)
├─ id (PK)
├─ email (UNIQUE)
├─ password
├─ role
└─ created_at

orders (주문)
├─ id (PK)
├─ user_id (FK → users.id)
├─ amount
├─ status
├─ created_at
└─ updated_at

payments (결제)
├─ id (PK)
├─ order_id (FK → orders.id)
├─ amount
├─ refunded_amount
├─ status
├─ payment_method
├─ pg_transaction_id
├─ captured_at
├─ created_at
└─ updated_at

refunds (환불)
├─ id (PK)
├─ payment_id (FK → payments.id)
├─ amount
├─ status
├─ reason
├─ idempotency_key
├─ requested_at
├─ completed_at
├─ created_at
└─ updated_at

settlements (정산)
├─ id (PK)
├─ payment_id (FK → payments.id)
├─ order_id (FK → orders.id)
├─ amount
├─ status
├─ settlement_date
├─ confirmed_at
├─ approved_by (FK → users.id)
├─ approved_at
├─ rejected_by (FK → users.id)
├─ rejected_at
├─ rejection_reason
├─ created_at
└─ updated_at

settlement_adjustments (정산 조정)
├─ id (PK)
├─ settlement_id (FK → settlements.id)
├─ refund_id (FK → refunds.id)
├─ amount
├─ status
├─ adjustment_date
├─ confirmed_at
├─ created_at
└─ updated_at

settlement_schedule_config (정산 스케줄 설정)
├─ id (PK)
├─ config_key (UNIQUE)
├─ cron_expression
├─ enabled
├─ description
├─ merchant_id
├─ created_at
└─ updated_at
```

---

## 📝 상태 코드별 의미

### HTTP 상태 코드
- `200 OK`: 성공
- `201 Created`: 리소스 생성 성공
- `400 Bad Request`: 잘못된 요청
- `401 Unauthorized`: 인증 실패
- `403 Forbidden`: 권한 없음
- `404 Not Found`: 리소스 없음
- `409 Conflict`: 중복 (예: 이메일 중복)
- `500 Internal Server Error`: 서버 오류

---

## 🎯 핵심 기능 요약

1. **주문/결제 관리**: 주문 생성 → 결제 → 매입 확정
2. **환불 처리**: 환불 요청 → 승인 → 정산 조정
3. **정산 자동화**: 스케줄러 기반 자동 정산 생성
4. **정산 승인 프로세스**: 관리자 검토 및 승인/반려
5. **검색 및 집계**: Elasticsearch 기반 고속 검색 및 통계
6. **동적 스케줄 설정**: DB 기반 스케줄 관리

---

**작성일**: 2026-02-12
**버전**: 1.0.0
