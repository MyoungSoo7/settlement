# Lemuel - OpsLab 주문·결제·정산 시스템

Spring Boot 기반의 JWT 인증 + 주문/결제/정산 통합 시스템입니다.

## 📋 프로젝트 개요

- **프로젝트명**: Lemuel (인증·주문·결제·정산 통합 시스템)
- **버전**: 0.0.1-SNAPSHOT
- **Java**: 21
- **Spring Boot**: 3.5.10
- **데이터베이스**: PostgreSQL 16

## 🏗️ 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                         Client                              │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                    Spring Security                          │
│                  (JWT Filter Chain)                         │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                     Controllers                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │AuthController│  │OrderController│  │PaymentControl│     │
│  │ /auth/login  │  │   /orders    │  │  /payments   │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                    Service Layer                            │
│           SettlementBatchService (일 단위 배치)             │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                   Repository Layer                          │
│  UserRepo  │  OrderRepo  │  PaymentRepo  │ SettlementRepo  │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                  PostgreSQL Database                        │
│        users │ orders │ payments │ settlements             │
└─────────────────────────────────────────────────────────────┘
```

## 📊 주문/결제/정산 상태 전이 다이어그램

### 주문(Order) 상태
- **CREATED**: 주문 생성됨(결제 전)
- **PAID**: 결제 완료로 주문 확정
- **CANCELED**: 결제 전 취소
- **REFUNDED**: 결제 후 환불 완료

### 결제(Payment) 상태
- **READY**: 결제 생성(요청 준비)
- **AUTHORIZED**: 승인됨(카드/간편결제 승인)
- **CAPTURED**: 매입/확정(실 결제 완료)
- **FAILED**: 실패
- **CANCELED**: 승인 취소
- **REFUNDED**: 환불

### 정산(Settlement) 상태
- **PENDING**: 정산 대상 생성(아직 확정 전)
- **CONFIRMED**: 정산 금액 확정(회계 기준 확정)
- **CANCELED**: 정산 취소(환불/취소 반영)

### 상태 전이 흐름

```
[Order] CREATED
   | (결제 시작)
   v
[Payment] READY -> AUTHORIZED -> CAPTURED
   |                         |
   | (실패)                  | (결제완료 이벤트)
   v                         v
[Payment] FAILED         [Order] PAID
                             |
                             | (정산대상 생성 - 매일 새벽 2시 배치)
                             v
                        [Settlement] PENDING
                             |
                             | (정산확정 - 매일 새벽 3시 배치)
                             v
                        [Settlement] CONFIRMED
                             |
                             | (환불/취소 발생)
                             v
                        [Settlement] CANCELED
                             ^
                             |
[Payment] REFUNDED  <--------+
   |
   v
[Order] REFUNDED
```

### 취소/환불 분기

#### 결제 전 취소
```
Order.CREATED -> Order.CANCELED
(Payment 없거나 Payment READY 취소)
```

#### 결제 후 환불
```
Payment.CAPTURED -> Payment.REFUNDED
Order.PAID -> Order.REFUNDED
Settlement.PENDING/CONFIRMED -> Settlement.CANCELED
```

## 🗂️ 프로젝트 구조

```
lemuel/
├── src/main/java/github/lms/lemuel/
│   ├── LemuelApplication.java          # 메인 애플리케이션 (@EnableScheduling)
│   ├── batch/
│   │   └── SettlementBatchService.java # 일 단위 정산 배치 작업
│   ├── config/
│   │   ├── JwtProperties.java          # JWT 설정 프로퍼티
│   │   └── JwtUtil.java                # JWT 토큰 생성/검증
│   ├── security/
│   │   ├── SecurityConfig.java         # Spring Security 설정
│   │   └── JwtAuthenticationFilter.java # JWT 인증 필터
│   ├── controller/
│   │   ├── AuthController.java         # 인증 API (/auth/login)
│   │   ├── UserController.java         # 사용자 API (/users)
│   │   ├── OrderController.java        # 주문 API (/orders)
│   │   └── PaymentController.java      # 결제 API (/payments)
│   ├── domain/
│   │   ├── User.java                   # 사용자 엔티티
│   │   ├── Order.java                  # 주문 엔티티
│   │   ├── Payment.java                # 결제 엔티티
│   │   └── Settlement.java             # 정산 엔티티
│   ├── repository/
│   │   ├── UserRepository.java         # 사용자 Repository
│   │   ├── OrderRepository.java        # 주문 Repository
│   │   ├── PaymentRepository.java      # 결제 Repository
│   │   └── SettlementRepository.java   # 정산 Repository
│   └── dto/
│       ├── LoginRequest/Response.java  # 로그인 DTO
│       ├── UserRegisterRequest/Response.java  # 사용자 DTO
│       ├── OrderCreateRequest/Response.java   # 주문 DTO
│       └── PaymentRequest/Response.java       # 결제 DTO
├── src/main/resources/
│   ├── application.yml                 # 애플리케이션 설정
│   └── db/migration/
│       ├── V1__init.sql                # 사용자 테이블 생성
│       └── V2__create_order_payment_settlement.sql  # 주문/결제/정산 테이블
├── docker-compose.yml                  # PostgreSQL Docker 설정
└── build.gradle.kts                    # Gradle 빌드 설정
```

## 🔧 기술 스택

### Backend
- **Spring Boot 3.5.10**
  - Spring Web
  - Spring Security
  - Spring Data JPA
  - Spring Validation
  - Spring Actuator

### Database
- **PostgreSQL 17**
- **Flyway** (DB 마이그레이션)

### Security
- **JWT (JSON Web Token)** - `io.jsonwebtoken:jjwt:0.12.5`
- **BCrypt** (비밀번호 암호화)

### Documentation
- **SpringDoc OpenAPI** - Swagger UI

## 📊 데이터베이스 스키마

### users 테이블
```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'USER' NOT NULL,
    created_at TIMESTAMP DEFAULT NOW() NOT NULL
);
```

### orders 테이블
```sql
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### payments 테이블
```sql
CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    payment_method VARCHAR(50),
    pg_transaction_id VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

### settlements 테이블 (정산 최소 스키마)
```sql
CREATE TABLE settlements (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    settlement_date DATE NOT NULL,          -- 정산 기준일
    confirmed_at TIMESTAMP,                 -- 확정 시각
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    FOREIGN KEY (payment_id) REFERENCES payments(id),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

## 🔍 인덱스 및 제약조건

### 핵심 제약조건
```sql
-- 1. order_id는 하나의 활성 결제만 가능 (1:1 관계)
CREATE UNIQUE INDEX idx_payments_order_id_unique
ON payments(order_id)
WHERE status IN ('READY', 'AUTHORIZED', 'CAPTURED');

-- 2. payment_id는 unique (하나의 결제에 하나의 정산)
CREATE UNIQUE INDEX idx_settlements_payment_id_unique
ON settlements(payment_id);
```

### 성능 최적화 인덱스
```sql
-- 배치 작업용 복합 인덱스
CREATE INDEX idx_payments_status_updated_at ON payments(status, updated_at);
CREATE INDEX idx_settlements_date_status ON settlements(settlement_date, status);

-- 조회 최적화
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_settlements_settlement_date ON settlements(settlement_date);
```

### 배치 실행 이력 (선택사항)
```sql
CREATE TABLE batch_run_history (
    id BIGSERIAL PRIMARY KEY,
    batch_name VARCHAR(100) NOT NULL,
    run_id VARCHAR(100) NOT NULL,           -- 배치 실행 고유 ID
    target_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    processed_count INT DEFAULT 0,
    error_message TEXT
);

CREATE INDEX idx_batch_history_run_id ON batch_run_history(run_id);
CREATE INDEX idx_batch_history_target_date ON batch_run_history(target_date);
```

## 🚀 시작하기

### 1. 사전 요구사항
- Java 21
- Docker & Docker Compose
- Gradle

### 2. PostgreSQL 실행
```bash
docker-compose up -d
```

### 3. 데이터베이스 생성
```bash
psql -U postgres -c "CREATE DATABASE opslab;"
```

또는 PostgreSQL에 접속해서:
```sql
CREATE DATABASE opslab;
CREATE USER inter WITH PASSWORD '1234';
GRANT ALL PRIVILEGES ON DATABASE opslab TO inter;
```

### 4. 애플리케이션 실행
```bash
./gradlew bootRun
```

## 📡 API 엔드포인트

### 인증 API

#### 1. 회원가입
```http
POST /users
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123"
}
```

#### 2. 로그인
```http
POST /auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123"
}
```

#### 3. 내 정보 조회
```http
GET /users/me
Authorization: Bearer {JWT_TOKEN}
```

### 주문 API

#### 1. 주문 생성
```http
POST /orders
Content-Type: application/json

{
  "userId": 1,
  "amount": 10000.00
}
```

#### 2. 주문 조회
```http
GET /orders/{orderId}
```

#### 3. 사용자별 주문 목록
```http
GET /orders/user/{userId}
```

#### 4. 주문 취소 (결제 전)
```http
PATCH /orders/{orderId}/cancel
```

### 결제 API

#### 1. 결제 생성
```http
POST /payments
Content-Type: application/json

{
  "orderId": 1,
  "paymentMethod": "CARD"
}
```

#### 2. 결제 승인
```http
PATCH /payments/{paymentId}/authorize
```

#### 3. 결제 확정 (매입)
```http
PATCH /payments/{paymentId}/capture
```

#### 4. 환불
```http
PATCH /payments/{paymentId}/refund
```

### 환불 API

#### 1. 전체 환불 (Full Refund)
```http
POST /refunds/full/{paymentId}
```
- Payment: CAPTURED → REFUNDED
- Order: PAID → REFUNDED
- Settlement: PENDING/CONFIRMED → CANCELED

#### 2. 부분 환불 (Partial Refund)
```http
POST /refunds/partial/{paymentId}?refundAmount=5000.00
```
- 음수 Payment 레코드 생성 (환불 금액)
- Order: PAID 유지
- Settlement: 금액 조정

#### 3. 결제 실패 환불 (Failed Payment Refund)
```http
POST /refunds/failed/{paymentId}
```
- Payment: AUTHORIZED/FAILED → CANCELED
- Order: CREATED 유지 (재결제 가능)
- Settlement: 없음

### 정산 배치 작업

#### Pseudo Code 흐름:

**1. 대상 조회 (매일 새벽 2시)**
```
BEGIN TRANSACTION
  targetDate = yesterday
  payments = SELECT * FROM payments
             WHERE status = 'CAPTURED'
             AND updated_at BETWEEN targetDate 00:00:00 AND 23:59:59

  FOR EACH payment IN payments:
    IF NOT EXISTS settlement WHERE payment_id = payment.id:
      INSERT INTO settlements (payment_id, order_id, amount, status, settlement_date)
      VALUES (payment.id, payment.order_id, payment.amount, 'PENDING', targetDate)
  END FOR
COMMIT
```

**2. 정산 확정 (매일 새벽 3시)**
```
BEGIN TRANSACTION
  targetDate = yesterday
  settlements = SELECT * FROM settlements
                WHERE settlement_date = targetDate AND status = 'PENDING'

  FOR EACH settlement IN settlements:
    UPDATE settlements
    SET status = 'CONFIRMED', confirmed_at = NOW()
    WHERE id = settlement.id
  END FOR
COMMIT
```

- **매일 새벽 2시**: 전날 `CAPTURED` 상태의 결제를 `PENDING` 정산 대상으로 생성
- **매일 새벽 3시**: 전날 생성된 `PENDING` 정산을 `CONFIRMED`로 확정

## 📖 Swagger UI

애플리케이션 실행 후 다음 URL에서 API 문서를 확인할 수 있습니다:

```
http://localhost:8080/swagger-ui.html
```

## 🔐 JWT 설정

`application.yml`에서 JWT 설정을 확인할 수 있습니다:

```yaml
app:
  jwt:
    issuer: lemuel-ops-lab
    secret: ops-lab-super-secret-key-must-be-at-least-32-chars-long-for-hmac
    ttl-seconds: 86400  # 24시간
```

## 📊 모니터링

Spring Actuator를 통해 애플리케이션 상태를 확인할 수 있습니다:

- Health: `http://localhost:8080/actuator/health`
- Info: `http://localhost:8080/actuator/info`
- Metrics: `http://localhost:8080/actuator/metrics`

## 🧪 테스트

```bash
./gradlew test
```

## 📝 환경 변수

개발 환경에서는 `application.yml`에 설정되어 있습니다.
프로덕션 환경에서는 다음 환경 변수를 설정하세요:

- `SPRING_DATASOURCE_URL`: 데이터베이스 URL
- `SPRING_DATASOURCE_USERNAME`: DB 사용자명
- `SPRING_DATASOURCE_PASSWORD`: DB 비밀번호
- `APP_JWT_SECRET`: JWT 비밀키
- `APP_JWT_TTL_SECONDS`: JWT 만료 시간(초)

## 🐛 트러블슈팅

### "데이터베이스 'opslab'이 없습니다" 에러
```bash
psql -U postgres -c "CREATE DATABASE opslab;"
```

### 사용자 권한 에러
```sql
GRANT ALL PRIVILEGES ON DATABASE opslab TO inter;
```

### 포트 충돌 (5432)
```bash
docker-compose down
docker-compose up -d
```

## 📄 라이선스

이 프로젝트는 내부 OpsLab 용도로 개발되었습니다.
