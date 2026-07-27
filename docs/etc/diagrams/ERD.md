# Lemuel ERD — 이커머스 + 정산 도메인

> Mermaid 형식. GitHub 에서 자동 렌더링됨.

## 핵심 테이블 관계

```mermaid
erDiagram
    USERS ||--o{ ORDERS : "places"
    USERS ||--o| CARTS : "has 1 active"
    USERS ||--o{ PRODUCTS : "owns (seller)"

    PRODUCTS ||--o{ PRODUCT_VARIANTS : "has SKUs"
    PRODUCTS ||--o{ PRODUCT_IMAGES : "has images"
    PRODUCTS }o--o| CATEGORIES : "belongs to"

    CARTS ||--o{ CART_ITEMS : "contains"
    CART_ITEMS }o--|| PRODUCTS : "references"
    CART_ITEMS }o--o| PRODUCT_VARIANTS : "optional SKU"

    ORDERS ||--o{ ORDER_ITEMS : "contains"
    ORDERS ||--o| PAYMENTS : "1:1"
    ORDERS ||--o| SHIPMENTS : "1:1"

    ORDER_ITEMS }o--|| PRODUCTS : "snapshot of"
    ORDER_ITEMS }o--o| PRODUCT_VARIANTS : "optional SKU"

    PAYMENTS ||--o{ REFUNDS : "partial refunds"
    PAYMENTS ||--|| SETTLEMENTS : "1:1 by payment_id"

    SETTLEMENTS ||--o{ SETTLEMENT_ADJUSTMENTS : "refund adjustments"

    PG_RECONCILIATION_RUNS ||--o{ PG_RECONCILIATION_DISCREPANCIES : "found in"
    PG_RECONCILIATION_DISCREPANCIES }o--o| PAYMENTS : "matched"

    OUTBOX_EVENTS ||--o| PROCESSED_EVENTS : "consumed by group"

    USERS {
        bigserial id PK
        varchar email UK
        varchar password_hash
        varchar role "USER/ADMIN/MANAGER"
        varchar seller_tier "NORMAL/VIP/STRATEGIC"
        varchar settlement_cycle "DAILY/WEEKLY/MONTHLY"
    }

    PRODUCTS {
        bigserial id PK
        varchar name UK
        text description
        numeric price
        int stock_quantity "옵션 없는 상품용"
        varchar status
        bigint category_id FK
        bigint seller_id FK "→ users.id"
    }

    PRODUCT_VARIANTS {
        bigserial id PK
        bigint product_id FK
        varchar sku UK
        varchar option_name "색상:빨강/사이즈:L"
        numeric additional_price
        int stock_quantity
        bigint version "Optimistic Lock"
        varchar status
    }

    CARTS {
        bigserial id PK
        bigint user_id UK
        timestamp last_active_at
    }

    CART_ITEMS {
        bigserial id PK
        bigint cart_id FK
        bigint product_id FK
        bigint variant_id FK "nullable"
        int quantity
    }

    ORDERS {
        bigserial id PK
        bigint user_id FK
        bigint product_id FK "nullable - 다건 주문은 NULL"
        numeric amount
        varchar status "CREATED/PAID/CANCELED/REFUNDED"
    }

    ORDER_ITEMS {
        bigserial id PK
        bigint order_id FK
        bigint product_id FK
        bigint variant_id FK "nullable"
        varchar sku "snapshot"
        varchar product_name "snapshot"
        numeric unit_price "주문 시점 가격"
        int quantity
        numeric line_amount
    }

    PAYMENTS {
        bigserial id PK
        bigint order_id FK
        numeric amount
        numeric refunded_amount
        varchar status "READY/AUTHORIZED/CAPTURED/REFUNDED"
        varchar payment_method
        varchar pg_transaction_id "TOSS:xxx / KCP:xxx prefix"
        timestamp captured_at
    }

    REFUNDS {
        bigserial id PK
        bigint payment_id FK
        numeric amount
        varchar status
        varchar idempotency_key "UQ with payment_id"
        varchar refund_type "FULL_REFUND/PARTIAL_REFUND"
    }

    SHIPMENTS {
        bigserial id PK
        bigint order_id FK_UK
        varchar recipient_name
        varchar phone
        varchar postal_code
        varchar address1
        varchar carrier "CJ대한통운/한진/우체국"
        varchar tracking_number
        varchar status "PENDING→READY→SHIPPED→IN_TRANSIT→DELIVERED→RETURNED"
    }

    SETTLEMENTS {
        bigserial id PK
        bigint payment_id FK_UK
        bigint order_id
        date settlement_date
        numeric payment_amount "결제 원금(gross)"
        numeric refunded_amount
        numeric commission
        numeric net_amount
        numeric commission_rate "정산 시점의 수수료율 - 이력 보존"
        numeric holdback_amount "보류금(등급별)"
        varchar status "REQUESTED/PROCESSING/DONE/FAILED"
    }

    SETTLEMENT_ADJUSTMENTS {
        bigserial id PK
        bigint settlement_id FK
        bigint refund_id FK "nullable - chargeback 경로는 NULL"
        bigint chargeback_id FK "nullable - refund 과 양립"
        numeric amount "역정산 음수 레코드"
        varchar status "PENDING/..."
        date adjustment_date
    }

    PG_RECONCILIATION_RUNS {
        bigserial id PK
        varchar pg_provider "TOSS/KCP/NICE/INICIS"
        date target_date
        varchar file_name
        varchar status "RUNNING/COMPLETED/FAILED"
        int matched_count
        int discrepancy_count
        int auto_corrected_count
        varchar operator_id
    }

    PG_RECONCILIATION_DISCREPANCIES {
        bigserial id PK
        bigint run_id FK
        varchar type "AMOUNT_MISMATCH/MISSING_INTERNAL/MISSING_PG/DUPLICATE/ROUNDING_DIFF"
        bigint payment_id "nullable"
        varchar pg_transaction_id
        numeric internal_amount
        numeric pg_amount
        numeric difference
        varchar status "PENDING/APPROVED/REJECTED/AUTO_CORRECTED"
    }

    OUTBOX_EVENTS {
        bigserial id PK
        varchar aggregate_type "Payment/Settlement"
        varchar aggregate_id
        varchar event_type
        uuid event_id UK
        jsonb payload
        varchar status "PENDING/PUBLISHED/FAILED"
        int retry_count "≥10 → FAILED + DLQ"
        text last_error
        varchar trace_parent "W3C - 비동기 trace 추적"
    }

    PROCESSED_EVENTS {
        varchar consumer_group PK
        uuid event_id PK
        timestamp processed_at
    }
```

## 테이블 그룹

| 그룹 | 테이블 | 책임 |
|------|--------|------|
| **회원** | `users` | 인증·권한·셀러 등급·정산 주기 |
| **상품** | `products`, `product_variants`, `product_images`, `categories`, `tags` | 카탈로그 + 옵션(SKU) + 카테고리 |
| **장바구니** | `carts`, `cart_items` | 사용자별 1 개의 활성 장바구니 |
| **주문** | `orders`, `order_items` | 다건 주문, 가격 스냅샷 |
| **결제** | `payments`, `refunds` | PG 거래 + 부분환불 + 멱등키 |
| **배송** | `shipments` | 운송장·상태머신 6 단계 |
| **정산** | `settlements`, `settlement_adjustments` | 셀러별 일/주/월 정산 + 역정산 |
| **PG 대사** | `pg_reconciliation_runs`, `pg_reconciliation_discrepancies` | PG 파일 비교 + 차액 분류 |
| **이벤트** | `outbox_events`, `processed_events` | Transactional Outbox + 멱등 컨슈머 |
| **감사** | `audit_logs` | 운영자 액션 + PII 마스킹 로그 |

## 멱등성 / 정합성 키

이커머스·결제 시스템의 정합성을 보장하는 핵심 제약:

| 키 | 위치 | 보장 |
|----|------|------|
| `outbox_events.event_id UNIQUE` | 프로듀서 | 같은 이벤트 2 회 outbox 기록 방지 |
| `processed_events PK(group, event_id)` | 컨슈머 | Kafka 재배달 시 중복 처리 방지 |
| `settlements.payment_id UNIQUE` | 정산 | 같은 결제 2 번 정산 생성 방지 |
| `refunds(payment_id, idempotency_key) UNIQUE` | 환불 | 동일 키 중복 환불 방지 |
| `cart_items(cart_id, product_id, variant_id) UNIQUE` | 장바구니 | 같은 SKU 2 라인 방지 (자동 수량 증가) |
| `product_variants.sku UNIQUE` | SKU | 외부 노출 식별자 충돌 방지 |
| `product_variants.version` | SKU | Optimistic Lock — 동시 재고 차감 |
