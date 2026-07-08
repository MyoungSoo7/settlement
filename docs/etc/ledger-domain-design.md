# Ledger 도메인 설계 문서

> **배경**: settlement 도메인 이후에 추가될 ledger(원장) 도메인의 설계 분석 문서입니다.
> 기존 프로젝트 구조를 기반으로 헥사고날 아키텍처 패턴에 맞게 작성했습니다.

---

## 1. 현재 프로젝트 구조 요약

### 1.1 아키텍처 패턴

헥사고날 아키텍처(Hexagonal Architecture)를 엄격하게 따릅니다.

```
{domain}/
├── domain/                  # 순수 비즈니스 로직 (프레임워크 무의존)
├── application/
│   ├── port/
│   │   ├── in/             # Inbound 포트 (UseCase 인터페이스)
│   │   └── out/            # Outbound 포트 (저장소·외부 인터페이스)
│   ├── service/            # UseCase 구현체
│   └── dto/                # 애플리케이션 계층 DTO
└── adapter/
    ├── in/                 # Web Controller, Batch Tasklet, Event Listener
    └── out/                # JPA 어댑터, ES 어댑터, 이벤트 발행
```

### 1.2 기존 도메인 목록

| 도메인 | 역할 |
|--------|------|
| `user` | 사용자 인증, 비밀번호 재설정 |
| `order` | 주문 생성 및 상태 관리 |
| `payment` | PG 결제 처리 (승인/확정/환불) |
| `settlement` | 정산 생성, 확정, 환불 조정 |
| `category` | 상품 카테고리 관리 |
| `coupon` | 쿠폰 발급 및 사용 |

---

## 2. 기존 도메인 흐름

### 2.1 핵심 거래 흐름

```
주문 생성 (Order: CREATED)
    ↓
결제 요청 (Payment: READY)
    ↓
PG 승인 (Payment: AUTHORIZED)
    ↓
매입 확정 (Payment: CAPTURED)  ← 정산 대상 확정
    ↓
일배치 실행 (T+1, 매일 새벽)
    ↓
정산 생성 (Settlement: REQUESTED)
    - paymentAmount: 원 결제금액
    - commission: paymentAmount × 3%
    - netAmount: paymentAmount - commission
    ↓
정산 확정 (Settlement: DONE / CONFIRMED)
```

### 2.2 환불 흐름

```
환불 요청 (refunds 테이블 생성)
    ↓
Payment.refundedAmount += refundAmount
    ↓
Settlement.adjustForRefund(refundAmount)
    - refundedAmount 누적
    - netAmount 재계산: (paymentAmount - refundedAmount) - commission
    - netAmount ≤ 0 → Settlement.status = CANCELED
    ↓
settlement_adjustments 테이블에 음수(-) 조정 기록
```

### 2.3 현재 DB 스키마 (핵심 테이블)

```sql
-- 결제
payments (id, order_id, amount, refunded_amount, status, pg_transaction_id, captured_at)

-- 정산
settlements (id, payment_id, order_id, payment_amount, commission, net_amount,
             status, settlement_date, confirmed_at,
             approved_by, approved_at, rejected_by, rejected_at)

-- 환불
refunds (id, payment_id, amount, status, reason, idempotency_key, completed_at)

-- 정산 조정
settlement_adjustments (id, settlement_id, refund_id, amount, status, adjustment_date)
```

### 2.4 Settlement 상태 머신

```
REQUESTED → PROCESSING → DONE
                       ↘ FAILED → REQUESTED (재시도)

(레거시 호환)
PENDING / WAITING_APPROVAL → CONFIRMED
어디서든 → CANCELED
```

---

## 3. Ledger 도메인이 필요한 이유

Settlement는 **"얼마를 정산해야 하는가"**를 계산합니다.
Ledger는 **"그 정산이 회계적으로 어떻게 기록되는가"**를 담당합니다.

현재 settlement 도메인의 한계:
- 정산 확정(DONE) 이후 실제 자금 이동 기록이 없음
- 수수료 수익이 어느 계정에 쌓이는지 추적 불가
- 환불 발생 시 회계 역분개 기록 없음
- 기간별 손익 집계, 미지급금 잔액 조회 불가

---

## 4. Ledger 도메인 개요

### 4.1 개념

**원장(Ledger)**은 모든 금전 거래를 **복식부기(Double-Entry Bookkeeping)** 방식으로 기록합니다.
하나의 거래는 반드시 차변(Debit)과 대변(Credit) 두 개의 분개 항목으로 구성되며, 합계는 항상 일치합니다.

### 4.2 이 프로젝트에서의 역할

```
Settlement → DONE
    ↓ (이벤트 발행 or 배치 트리거)
LedgerEntry 생성
    - (차) 미지급금(판매자)    / (대) 매출
    - (차) 수수료비용(판매자)  / (대) 수수료수익(플랫폼)
    ↓
환불 발생 시 역분개
    - (차) 매출환불            / (대) 미지급금
```

---

## 5. 도메인 모델 설계

### 5.1 `LedgerEntry` (원장 항목)

하나의 원장 항목은 **한 쌍의 분개(차변+대변)**를 나타냅니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long | PK |
| `referenceId` | Long | 참조 ID (settlementId 또는 refundId) |
| `referenceType` | ReferenceType | 참조 유형 (SETTLEMENT, REFUND) |
| `entryType` | LedgerEntryType | 분개 유형 (SETTLEMENT_CREATED, REFUND_REVERSED 등) |
| `debitAccount` | AccountType | 차변 계정 |
| `creditAccount` | AccountType | 대변 계정 |
| `amount` | BigDecimal | 거래 금액 (항상 양수) |
| `status` | LedgerStatus | PENDING → POSTED → REVERSED |
| `postedAt` | LocalDateTime | 전기 일시 |
| `settlementDate` | LocalDate | 정산 기준일 |
| `memo` | String | 분개 메모 |
| `createdAt` | LocalDateTime | 생성일시 |

### 5.2 `AccountType` (계정과목)

```
ACCOUNTS_RECEIVABLE   // 미수금 (판매자가 받을 돈)
ACCOUNTS_PAYABLE      // 미지급금 (플랫폼이 판매자에게 지급할 돈)
REVENUE               // 매출 (상품 판매 수익)
COMMISSION_REVENUE    // 수수료 수익 (플랫폼 수익)
COMMISSION_EXPENSE    // 수수료 비용 (판매자 부담 비용)
SALES_REFUND          // 매출환불
CASH                  // 현금 (실제 이체 시)
```

### 5.3 `LedgerEntryType` (분개 유형)

```
SETTLEMENT_CREATED    // 정산 생성 시 최초 분개
SETTLEMENT_CONFIRMED  // 정산 확정 시 전기
REFUND_REVERSED       // 환불에 의한 역분개
COMMISSION_RECOGNIZED // 수수료 인식
PAYOUT_EXECUTED       // 실 이체(출금) 실행
```

### 5.4 `LedgerStatus` (원장 상태)

```
PENDING   // 분개 작성됨, 미전기
POSTED    // 전기 완료 (잔액에 반영됨)
REVERSED  // 역분개 처리됨 (환불 등)
```

### 5.5 `AccountBalance` (계정 잔액, 선택적)

집계용 보조 테이블. 계정별 누적 잔액을 관리합니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long | PK |
| `accountType` | AccountType | 계정과목 |
| `balanceDate` | LocalDate | 잔액 기준일 |
| `debitTotal` | BigDecimal | 당일 차변 합계 |
| `creditTotal` | BigDecimal | 당일 대변 합계 |
| `balance` | BigDecimal | 잔액 (debitTotal - creditTotal) |

---

## 6. 분개 패턴

### 6.1 정산 생성 시 (Settlement: DONE)

```
거래: 결제금액 10,000원, 수수료 300원(3%), 순정산액 9,700원

(차) 미지급금(ACCOUNTS_PAYABLE)   9,700  ← 판매자에게 지급할 금액
(차) 수수료수익(COMMISSION_REVENUE)  300  ← 플랫폼 수익
(대) 매출(REVENUE)               10,000  ← 판매 발생
```

### 6.2 환불 발생 시 (Settlement 조정 후)

```
환불금액: 10,000원 (전액 환불)

(차) 매출환불(SALES_REFUND)       10,000  ← 매출 취소
(대) 미지급금(ACCOUNTS_PAYABLE)    9,700  ← 지급 취소
(대) 수수료수익(COMMISSION_REVENUE)  300  ← 수수료 반환
```

### 6.3 실 이체 시 (Payout 실행)

```
(차) 미지급금(ACCOUNTS_PAYABLE)    9,700
(대) 현금(CASH)                    9,700
```

---

## 7. 이벤트 플로우 (Settlement → Ledger)

현재 settlement 도메인은 이미 `PublishSettlementEventPort`와 `SettlementIndexEventListener` 패턴을 사용합니다.
같은 패턴으로 Ledger 이벤트를 연결합니다.

```
Settlement DONE
    ↓ SettlementEventPublisherAdapter (기존 패턴 재사용)
    ↓ ApplicationEventPublisher.publishEvent(LedgerCreationEvent)
    ↓
LedgerCreationEventListener (adapter/in/event/)
    ↓ CreateLedgerEntryUseCase.createFromSettlement(settlementId)
    ↓
LedgerEntry 생성 + 저장 (PENDING → POSTED)
```

---

## 8. 헥사고날 아키텍처 적용

### 8.1 패키지 구조

```
github.lms.lemuel.ledger/
├── domain/
│   ├── LedgerEntry.java
│   ├── AccountType.java          (enum)
│   ├── LedgerEntryType.java      (enum)
│   ├── LedgerStatus.java         (enum)
│   └── exception/
│       └── LedgerNotFoundException.java
│
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── CreateLedgerEntryUseCase.java
│   │   │   ├── GetLedgerUseCase.java
│   │   │   └── ReverseEntryUseCase.java
│   │   └── out/
│   │       ├── SaveLedgerEntryPort.java
│   │       ├── LoadLedgerEntryPort.java
│   │       └── LoadSettlementForLedgerPort.java
│   └── service/
│       ├── CreateLedgerEntryService.java
│       ├── GetLedgerService.java
│       └── ReverseEntryService.java
│
└── adapter/
    ├── in/
    │   ├── event/
    │   │   ├── LedgerCreationEventListener.java
    │   │   └── dto/
    │   │       └── LedgerCreationEvent.java
    │   └── web/
    │       ├── LedgerController.java
    │       └── response/
    │           └── LedgerEntryResponse.java
    └── out/
        └── persistence/
            ├── LedgerJpaEntity.java
            ├── LedgerPersistenceAdapter.java
            ├── SpringDataLedgerJpaRepository.java
            └── LedgerPersistenceMapper.java
```

### 8.2 포트 인터페이스 요약

**Inbound 포트 (UseCase)**

| 인터페이스 | 메서드 | 호출 시점 |
|-----------|--------|----------|
| `CreateLedgerEntryUseCase` | `createFromSettlement(settlementId)` | Settlement DONE 이벤트 |
| `ReverseEntryUseCase` | `reverseEntry(ledgerEntryId, reason)` | 환불 완료 이벤트 |
| `GetLedgerUseCase` | `getBySettlementId(id)`, `getByDateRange(from, to)` | 조회 API |

**Outbound 포트 (Port)**

| 인터페이스 | 역할 |
|-----------|------|
| `SaveLedgerEntryPort` | 원장 항목 저장 |
| `LoadLedgerEntryPort` | 원장 항목 조회 |
| `LoadSettlementForLedgerPort` | 정산 데이터 조회 (settlement 도메인 의존 없이) |

---

## 9. DB 스키마 (제안)

```sql
-- 원장 항목
CREATE TABLE ledger_entries (
    id               BIGSERIAL PRIMARY KEY,
    reference_id     BIGINT        NOT NULL,
    reference_type   VARCHAR(30)   NOT NULL,        -- SETTLEMENT, REFUND
    entry_type       VARCHAR(50)   NOT NULL,        -- SETTLEMENT_CREATED 등
    debit_account    VARCHAR(50)   NOT NULL,        -- 차변 계정
    credit_account   VARCHAR(50)   NOT NULL,        -- 대변 계정
    amount           DECIMAL(12,2) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    settlement_date  DATE          NOT NULL,
    posted_at        TIMESTAMP,
    memo             VARCHAR(500),
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_ledger_amount CHECK (amount > 0)
);

CREATE INDEX idx_ledger_reference      ON ledger_entries(reference_id, reference_type);
CREATE INDEX idx_ledger_entry_type     ON ledger_entries(entry_type);
CREATE INDEX idx_ledger_status         ON ledger_entries(status);
CREATE INDEX idx_ledger_settlement_date ON ledger_entries(settlement_date);
CREATE INDEX idx_ledger_debit_account  ON ledger_entries(debit_account);
CREATE INDEX idx_ledger_credit_account ON ledger_entries(credit_account);

-- 계정 일별 잔액 집계 (선택적)
CREATE TABLE account_balances (
    id            BIGSERIAL PRIMARY KEY,
    account_type  VARCHAR(50)   NOT NULL,
    balance_date  DATE          NOT NULL,
    debit_total   DECIMAL(14,2) NOT NULL DEFAULT 0,
    credit_total  DECIMAL(14,2) NOT NULL DEFAULT 0,
    balance       DECIMAL(14,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_account_balance UNIQUE (account_type, balance_date)
);
```

---

## 10. API 설계 (제안)

> SecurityConfig에서 `/ledger/**`는 `ADMIN`, `MANAGER` 역할만 접근 허용 예정

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/ledger/settlements/{settlementId}` | 특정 정산의 원장 항목 조회 |
| `GET` | `/ledger/entries` | 기간별 원장 항목 목록 (`?from=&to=&account=`) |
| `GET` | `/ledger/balances` | 계정별 잔액 조회 (`?date=&account=`) |
| `POST` | `/ledger/entries/{id}/reverse` | 원장 항목 역분개 (수동 처리용) |

---

## 11. Settlement와의 연동 포인트

### 11.1 기존 `SettlementEventPublisherAdapter` 확장

현재 `PublishSettlementEventPort` → `SettlementEventPublisherAdapter` 패턴이 ES 인덱싱에 사용됩니다.
동일한 패턴으로 `LedgerCreationEvent`를 추가 발행하면 됩니다.

### 11.2 Ledger 생성 트리거 시점

| 트리거 | Settlement 상태 | 분개 유형 |
|--------|----------------|----------|
| `CreateDailySettlementsService` 완료 | REQUESTED | SETTLEMENT_CREATED |
| `ConfirmSettlementsTasklet` 완료 | DONE | SETTLEMENT_CONFIRMED |
| `AdjustSettlementForRefundService` 완료 | CANCELED 또는 조정 | REFUND_REVERSED |

### 11.3 주의사항

- `LoadSettlementForLedgerPort`를 통해 settlement 도메인에 의존할 때, **직접 `Settlement` 클래스를 참조하지 않고** 별도 DTO(`SettlementSummary` 등)를 사용하여 도메인 간 결합도를 낮춥니다.
- Ledger 항목은 **불변(Immutable)** 원칙을 권장합니다. 수정이 필요할 경우 역분개 생성 후 새 항목을 추가합니다.
- 차변 합계와 대변 합계의 일치 여부를 생성 시점에 도메인 레이어에서 검증합니다.

---

## 12. 기존 코드에서 재사용 가능한 패턴

| 기존 패턴 | Ledger에서 재사용 방법 |
|-----------|----------------------|
| `Settlement.createFromPayment()` 정적 팩토리 | `LedgerEntry.createFromSettlement()` 동일 패턴 |
| `SettlementStatus.canTransitionTo()` 상태 전이 검증 | `LedgerStatus.canTransitionTo()` 동일 구현 |
| `PublishSettlementEventPort` + `SettlementEventPublisherAdapter` | `PublishLedgerEventPort` 동일 패턴 |
| `CreateSettlementsTasklet` (얇은 어댑터) | `PostLedgerEntriesTasklet` 동일 구조 |
| `NoOpSettlementSearchAdapter` (환경별 No-Op 구현) | 필요 시 `NoOpLedgerPort` 동일 패턴 |
| `SettlementBatchHealthIndicator` | `LedgerBatchHealthIndicator` 동일 구조 |

---

## 13. 구현 순서 제안

1. **DB 마이그레이션** — `V22__create_ledger_entries_table.sql`
2. **도메인 모델** — `LedgerEntry`, `AccountType`, `LedgerEntryType`, `LedgerStatus`
3. **포트 인터페이스** — `CreateLedgerEntryUseCase`, `SaveLedgerEntryPort`, `LoadLedgerEntryPort`
4. **서비스** — `CreateLedgerEntryService` (분개 생성 + 저장)
5. **영속성 어댑터** — `LedgerJpaEntity`, `LedgerPersistenceAdapter`
6. **이벤트 연동** — `LedgerCreationEventListener` (Settlement DONE 수신)
7. **환불 역분개** — `ReverseEntryService` + `ReverseEntryUseCase`
8. **조회 API** — `LedgerController`, `GetLedgerService`
9. **SecurityConfig 수정** — `/ledger/**` 권한 추가