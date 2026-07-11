# 시스템 아키텍처 & 설계

> ⚠️ **STALE — v0.2.0 모놀리스 시절 도메인 스냅샷.** 아래 정산 상태 모델
> (`PENDING/CONFIRMED/CALCULATED/WAITING_APPROVAL/APPROVED/REJECTED`)과 새벽 2시/3시 배치 흐름은
> 현재 코드와 다릅니다. 현재 정산 상태머신은 **`REQUESTED → PROCESSING → DONE`(→ FAILED / CANCELED)**
> (`SettlementStatus`)이고, 정산 생성은 배치가 아니라 **이벤트 드리븐**(`lemuel.payment.captured` →
> `PaymentEventKafkaConsumer`)이며 확정만 Spring Batch(`SettlementConfirmJobConfig`)로 처리합니다.
> 또 order↔settlement 는 DB-per-service 로 물리 분리됐습니다(ADR 0020). 최신 구조·상태 전이는 루트
> [`README.md`](../../README.md) · [`CLAUDE.md`](../../CLAUDE.md) · `docs/adr/0002-settlement-state-machine.md` 참조.
> 아래는 이력 보존용.

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
│  │AuthController│  │OrderController│  │RefundControl │     │
│  │ /auth/login  │  │   /orders    │  │   /refunds   │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
│  ┌──────────────────────────────────────────────────┐     │
│  │    SettlementSearchController                     │     │
│  │    /api/settlements/search (Elasticsearch)        │     │
│  └──────────────────────────────────────────────────┘     │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                    Service Layer                            │
│  RefundService + SettlementAdjustmentService                │
│  SettlementBatchService (일 단위 배치)                      │
│  SettlementIndexService (Elasticsearch 색인)                │
│  SettlementSearchService (복합 검색)                        │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              Repository Layer                               │
│  Refund | SettlementAdjustment | Payment | Settlement       │
│  SettlementSearchRepository (Elasticsearch)                 │
└─────────┬───────────────────────────────────────┬───────────┘
          │                                       │
          ▼                                       ▼
┌──────────────────────┐            ┌──────────────────────┐
│ PostgreSQL Database  │            │   Elasticsearch      │
│  refunds             │            │  settlement_search   │
│  settlement_adj...   │            │  (검색 인덱스)       │
│  payments            │            └──────────────────────┘
│  settlements         │
└──────────────────────┘
```

## 📐 도메인 모델 설계

### 주문·결제·정산 상태 전이 다이어그램

#### 주문(Order) 상태
- **CREATED**: 주문 생성됨(결제 전)
- **PAID**: 결제 완료로 주문 확정
- **CANCELED**: 결제 전 취소
- **REFUNDED**: 결제 후 환불 완료

#### 결제(Payment) 상태
- **READY**: 결제 생성(요청 준비)
- **AUTHORIZED**: 승인됨(카드/간편결제 승인)
- **CAPTURED**: 매입/확정(실 결제 완료)
- **FAILED**: 실패
- **CANCELED**: 승인 취소
- **REFUNDED**: 전액 환불 완료

#### 환불(Refund) 상태 - v0.2.0 신규
- **REQUESTED**: 환불 요청됨
- **APPROVED**: 환불 승인됨
- **COMPLETED**: 환불 완료
- **FAILED**: 환불 실패
- **CANCELED**: 환불 취소

#### 정산(Settlement) 상태
- **PENDING**: 정산 대상 생성(아직 확정 전)
- **CONFIRMED**: 정산 금액 확정(회계 기준 확정)
- **CALCULATED**: 정산 계산 완료
- **WAITING_APPROVAL**: 승인 대기
- **APPROVED**: 승인 완료
- **REJECTED**: 승인 거부

#### 정산 조정(SettlementAdjustment) 상태 - v0.2.0 신규
- **PENDING**: 조정 대기 중
- **CONFIRMED**: 조정 확정

### 환불 처리 흐름 (v0.2.0)

```
[Payment] CAPTURED (amount: 10000, refundedAmount: 0)
   |
   | (부분환불 3000원 요청 + Idempotency-Key)
   v
[Refund] REQUESTED -> COMPLETED (amount: 3000)
   |
   v
[Payment] CAPTURED (amount: 10000, refundedAmount: 3000)
   |
   | (부분환불 7000원 요청)
   v
[Refund] REQUESTED -> COMPLETED (amount: 7000)
   |
   v
[Payment] REFUNDED (amount: 10000, refundedAmount: 10000)
```

### 정산 확정 후 환불 시 조정 생성

```
[Settlement] CONFIRMED (amount: 10000)
   |
   | (환불 2000원 발생)
   v
[SettlementAdjustment] PENDING (amount: -2000, refund_id: ...)
   |
   | (새벽 3시 10분 배치)
   v
[SettlementAdjustment] CONFIRMED
```

## 🗄️ 데이터베이스 스키마 (v0.2.0)

### payments 테이블 (변경)
```sql
CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    refunded_amount DECIMAL(10, 2) NOT NULL DEFAULT 0,  -- 신규
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    payment_method VARCHAR(50),
    pg_transaction_id VARCHAR(100),
    captured_at TIMESTAMP,                              -- 신규
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payments_refunded_amount
        CHECK (refunded_amount >= 0 AND refunded_amount <= amount)
);
```

### refunds 테이블 (신규)
```sql
CREATE TABLE refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    reason TEXT,
    idempotency_key VARCHAR(255) NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payments(id),
    CONSTRAINT chk_refunds_amount CHECK (amount > 0)
);

-- 멱등성 보장: 동일 paymentDomain + idempotency_key 중복 방지
CREATE UNIQUE INDEX idx_refunds_payment_idempotency
ON refunds(payment_id, idempotency_key);
```

### settlement_adjustments 테이블 (신규)
```sql
CREATE TABLE settlement_adjustments (
    id BIGSERIAL PRIMARY KEY,
    settlement_id BIGINT NOT NULL,
    refund_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    adjustment_date DATE NOT NULL,
    confirmed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_adjustment_settlement FOREIGN KEY (settlement_id) REFERENCES settlements(id),
    CONSTRAINT fk_adjustment_refund FOREIGN KEY (refund_id) REFERENCES refunds(id),
    CONSTRAINT chk_adjustments_amount CHECK (amount < 0)
);

-- 환불 1건당 조정 1건 보장
CREATE UNIQUE INDEX idx_adjustments_refund_id_unique
ON settlement_adjustments(refund_id);
```

### settlements 테이블
```sql
CREATE TABLE settlements (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    settlement_date DATE NOT NULL,
    confirmed_at TIMESTAMP,
    approver_id BIGINT,                                -- 승인자 ID
    approved_at TIMESTAMP,                             -- 승인 일시
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_settlement_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);
```

## 🔄 v0.2.0 부분환불 리팩토링

### 주요 변경사항

**환불 모델 개선**:
- ❌ **이전**: 부분환불 시 음수 Payment 레코드 생성 (비표준, 조회/회계 복잡도 증가)
- ✅ **현재**: Refund 엔티티로 환불 이력 분리 관리 (실무 표준 패턴)

**새로운 기능**:
1. **멱등성 보장**: `Idempotency-Key` 헤더 기반 중복 환불 방지
2. **동시성 제어**: Payment row-level lock (PESSIMISTIC_WRITE)으로 환불 금액 초과 방지
3. **정산 조정**: CONFIRMED 정산 후 환불 시 `SettlementAdjustment` 생성 (회계 감사 추적)
4. **환불 누적 추적**: `Payment.refundedAmount`로 실시간 환불 누적 관리

### 도메인 모델 변경

```
Payment (원결제)
  - refundedAmount: 환불 누적 합계 (0 ~ amount)
  - status: REFUNDED (전액 환불 시)

Refund (환불 이력) - 신규 추가
  - payment_id, amount, status, idempotency_key
  - (payment_id, idempotency_key) UNIQUE 제약

SettlementAdjustment (정산 조정) - 신규 추가
  - settlement_id, refund_id, amount(음수)
  - CONFIRMED 정산에 대한 환불 처리용
```

## 🔐 보안 설계

### 데이터베이스 제약 조건
- ✅ `payments.refunded_amount` CHECK (0 ~ amount)
- ✅ `refunds(payment_id, idempotency_key)` UNIQUE
- ✅ `settlement_adjustments(refund_id)` UNIQUE
- ✅ `refunds.amount` CHECK (> 0)
- ✅ `settlement_adjustments.amount` CHECK (< 0)

### 멱등성 보장
- ✅ 동일 `Idempotency-Key` 재요청 시 동일 Refund 반환
- ✅ 환불 금액 중복 반영 방지

### 동시성 제어
- ✅ `PESSIMISTIC_WRITE` lock으로 동시 환불 요청 직렬화
- ✅ `refundedAmount` 초과 방지

### 배치 재실행 안정성
- ✅ Settlement 중복 생성 방지 (`findByPaymentId` 체크)
- ✅ Adjustment 중복 생성 방지 (`findByRefundId` 체크)

## 📊 모니터링 & 검색 아키텍처

### Prometheus & Grafana (성능 모니터링)
- **Prometheus**: `/actuator/prometheus` 엔드포인트로 메트릭 수집
- **Grafana**: 실시간 대시보드 및 알림 설정
- **Slack 알림**: 에러율/응답시간 임계치 초과 시 자동 알림

### Elasticsearch (정산 검색)
- **settlement_search 인덱스**: 정산/주문/결제/환불 통합 데이터
- **Nori Analyzer**: 한글 형태소 분석 (결제 수단, 환불 사유 등)
- **복합 검색 API**: 기간/금액/상태별 필터링 + 집계

## ⚙️ 정산 배치 작업

- **매일 새벽 2시**: 전날 `CAPTURED` 결제 → `PENDING` 정산 생성
- **매일 새벽 3시**: `PENDING` 정산 → `CONFIRMED` 확정
- **매일 새벽 3시 10분**: `PENDING` 정산 조정 → `CONFIRMED` 확정 (v0.2.0)
- **정산 데이터 Elasticsearch 색인**: 정산 생성/수정 시 자동 색인 (Spring Event 기반)

## 🏛️ 인프라 구성 전략

이 프로젝트는 **하이브리드 인프라 구성**을 사용합니다:

| 컴포넌트 | 환경 | 이유 |
|---------|------|------|
| **PostgreSQL** | 로컬 설치 | 프로덕션 환경과 동일한 설정, 성능 최적화 |
| **Elasticsearch** | Cloud (Elastic Cloud) | 확장성, 관리 용이성, 프로덕션 대비 |
| **Prometheus** | Docker | 개발 환경 전용 메트릭 수집 |
| **Grafana** | Docker | 개발 환경 전용 시각화 대시보드 |
