# 데이터 모델링 / ERD

| 항목 | 내용 |
|------|------|
| 문서번호 | LML-ERD-001 |
| 버전 | 1.1 |
| 작성일 | 2026-03-25 |
| 작성자 | DA (Data Architect) |
| 승인자 | TA |
| 문서상태 | 개선 |
| 기밀등급 | 대외비 |

---

## 버전 이력

| 버전 | 작성일 | 작성자 | 변경 내용 |
|------|--------|--------|-----------|
| 0.1 | 2026-03-25 | DA | 초안 작성 |
| 1.0 | 2026-03-25 | DA | 최종 확정 |
| 1.1 | 2026-03-25 | DA | 실제 Flyway 마이그레이션(V1~V21) 기반 테이블 20개 반영 |

---

## 1. 데이터베이스 개요

### 1.1 DBMS 정보

| 항목 | 내용 |
|------|------|
| DBMS | PostgreSQL |
| 버전 | 17 (Docker: postgres:17-alpine) |
| 스키마 | opslab |
| DB명 | inter (Docker) / opslab (local) |
| 포트 | 5432 (local) / 5433 (Docker) |
| 문자셋 | UTF-8 |
| 마이그레이션 | Flyway 11.7.2 (V1 ~ V21) |
| ORM | Spring Data JPA (Hibernate, ddl-auto: validate) |

### 1.2 설계 원칙
- 정규화 3NF 이상
- Flyway 마이그레이션으로 DDL 관리 (Hibernate ddl-auto: validate)
- 모든 테이블에 PK (BIGSERIAL) 적용
- 생성일시/수정일시 공통 컬럼
- MapStruct 기반 도메인 ↔ JPA 엔티티 변환

---

## 2. 전체 테이블 목록 (20개)

| No | 테이블명 | 도메인 | 마이그레이션 | 설명 |
|----|---------|--------|------------|------|
| 1 | users | user | V1 | 사용자 |
| 2 | password_reset_tokens | user | V11 | 비밀번호 재설정 토큰 |
| 3 | products | product | V10 | 상품 |
| 4 | product_images | product | V14 | 상품 이미지 |
| 5 | categories | product | V12 | 상품 카테고리 |
| 6 | tags | product | V12 | 상품 태그 |
| 7 | product_tags | product | V12 | 상품-태그 연결 |
| 8 | ecommerce_categories | category | V13 | 다계층 이커머스 카테고리 |
| 9 | product_ecommerce_categories | category | V13 | 상품-이커머스카테고리 연결 |
| 10 | orders | order | V2 | 주문 |
| 11 | payments | payment | V2 | 결제 |
| 12 | refunds | payment | V4 | 환불 (멱등성키) |
| 13 | settlements | settlement | V2 | 정산 |
| 14 | settlement_adjustments | settlement | V4 | 정산 조정 (역정산) |
| 15 | settlement_index_queue | settlement | V5 | ES 인덱싱 큐 |
| 16 | settlement_schedule_config | settlement | V6 | 배치 스케줄 설정 |
| 17 | reviews | review | V19 | 리뷰 |
| 18 | coupons | coupon | V20 | 쿠폰 |
| 19 | coupon_usages | coupon | V20 | 쿠폰 사용 내역 |
| 20 | batch_run_history | common | V2 | Spring Batch 실행 이력 |

---

## 3. ERD (Entity-Relationship Diagram)

### 3.1 핵심 도메인 관계

```
┌──────────┐     ┌───────────┐     ┌────────────────┐
│  users   │     │ products  │     │   categories   │
│──────────│     │───────────│     │────────────────│
│ id (PK)  │     │ id (PK)   │────>│ id (PK)        │
│ email(UK)│     │ name      │     │ name           │
│ password │     │ price     │     └────────────────┘
│ role     │     │ stock     │
│(USER/    │     │ status    │     ┌────────────────┐
│ ADMIN/   │     │ seller_id │     │     tags       │
│ MANAGER) │     │───────────│     │────────────────│
└────┬─────┘     │product_id │     │ id (PK)        │
     │           └─────┬─────┘     │ name           │
     │                 │           └───────┬────────┘
     │    ┌────────────┤                   │
     │    │            │           ┌───────┴────────┐
     │    │     ┌──────┴──────┐    │  product_tags  │
     │    │     │product_images│    │  (join table)  │
     │    │     └─────────────┘    └────────────────┘
     │    │
┌────┴────┴──┐     ┌───────────────────┐
│   orders   │     │ecommerce_categories│
│────────────│     │───────────────────│
│ id (PK)    │     │ id (PK)           │
│ user_id(FK)│     │ parent_id(FK/self)│
│ product_id │     │ slug (UK)         │
│ status     │     │ depth             │
│(CREATED/   │     └───────────────────┘
│ CONFIRMED/ │
│ CANCELLED/ │
│ REFUNDED)  │
└────┬───────┘
     │
┌────┴───────┐     ┌─────────────┐
│  payments  │     │   refunds   │
│────────────│     │─────────────│
│ id (PK)    │     │ id (PK)     │
│ order_id   │     │ payment_id  │
│ amount     │     │ idempotency │
│ refunded_  │     │ _key (UK)   │
│  amount    │     │ status      │
│ status     │     │(REQUESTED/  │
│(READY/     │     │ COMPLETED/  │
│ AUTHORIZED/│     │ FAILED)     │
│ CAPTURED/  │     └─────────────┘
│ REFUNDED/  │
│ PARTIALLY_ │
│ REFUNDED/  │
│ CANCELED)  │
│ captured_at│
└────┬───────┘
     │
┌────┴───────────┐     ┌──────────────────────┐
│  settlements   │     │settlement_adjustments│
│────────────────│     │──────────────────────│
│ id (PK)        │     │ id (PK)              │
│ payment_id(FK) │     │ settlement_id(FK)    │
│ seller_id (FK) │     │ refund_id(FK)        │
│ amount         │     │ adjustment_amount    │
│ fee (3%)       │     └──────────────────────┘
│ net_amount     │
│ status         │     ┌──────────────────────┐
│(REQUESTED/     │     │settlement_index_queue│
│ PENDING/       │     │──────────────────────│
│ CONFIRMED/     │     │ settlement_id        │
│ FAILED/        │     │ retry_count          │
│ CANCELED)      │     │ status               │
│ failureReason  │     └──────────────────────┘
│ settlement_date│
└────────────────┘

┌──────────┐     ┌──────────────┐
│ reviews  │     │   coupons    │
│──────────│     │──────────────│
│ id (PK)  │     │ id (PK)      │
│ user_id  │     │ code (UK)    │
│product_id│     │ type         │
│ rating   │     │(PERCENTAGE/  │
│ content  │     │ FIXED_AMOUNT)│
└──────────┘     │ max_uses     │
                 │ expires_at   │
                 └──────┬───────┘
                        │
                 ┌──────┴───────┐
                 │coupon_usages │
                 │──────────────│
                 │ coupon_id    │
                 │ user_id      │
                 │ order_id     │
                 └──────────────┘
```

---

## 4. Flyway 마이그레이션 현황 (V1 ~ V21)

| 버전 | 파일명 | 내용 |
|------|--------|------|
| V1 | init.sql | users 테이블 생성 |
| V2 | create_order_payment_settlement.sql | orders, payments, settlements, batch_run_history |
| V3 | add_indexes_and_constraints.sql | 성능 인덱스, FK 제약조건 |
| V4 | refunds_and_settlement_adjustments.sql | refunds, settlement_adjustments |
| V5 | settlement_index_queue.sql | ES 인덱싱 실패 큐 |
| V6 | settlement_schedule_config.sql | 배치 스케줄 설정 |
| V7 | add_settlement_approval_fields.sql | 정산 승인/반려 필드 |
| V8 | add_user_status_column.sql | 사용자 상태 컬럼 |
| V9 | alter_settlements_split_amount.sql | 정산 금액 분리 |
| V10 | create_products_table.sql | products 테이블 |
| V11 | create_password_reset_tokens_table.sql | 비밀번호 재설정 토큰 |
| V12 | create_categories_and_tags_tables.sql | categories, tags, product_tags |
| V13 | create_ecommerce_categories_table.sql | 다계층 이커머스 카테고리 |
| V14 | create_product_images_table.sql | 상품 이미지 |
| V15 | add_product_id_to_orders.sql | orders.product_id FK |
| V16 | fix_pg_transaction_id_length.sql | pg_transaction_id 컬럼 확장 |
| V17 | seed_data.sql | 초기 테스트 데이터 |
| V18 | add_seed_manager.sql | MANAGER 역할 사용자 추가 |
| V19 | create_reviews_table.sql | reviews 테이블 |
| V20 | create_coupons_table.sql | coupons, coupon_usages |
| V21 | seed_january_2026_data.sql | 2026년 테스트 데이터 |

---

## 5. Elasticsearch 인덱스

### 5.1 정산 검색 인덱스

| 항목 | 내용 |
|------|------|
| 클러스터 | Elastic Cloud (8.17.0) |
| 인증 | Basic Auth (환경 변수) |
| 인덱싱 서비스 | IndexSettlementService |
| 실패 큐 | settlement_index_queue 테이블 |
| 검색 서비스 | SettlementSearchJdbcRepository |

**인덱스 필드:**

| 필드 | 타입 | 분석기 | 설명 |
|------|------|--------|------|
| id | keyword | - | 정산 ID |
| seller_id | keyword | - | 판매자 ID |
| amount | long | - | 결제 금액 |
| fee | long | - | 수수료 |
| net_amount | long | - | 정산 금액 |
| status | keyword | - | 정산 상태 |
| settlement_date | date | - | 정산일 |
| failure_reason | text | - | 실패 사유 |

---

## 6. 주요 인덱스 전략

| 테이블 | 인덱스 | 컬럼 | 유형 | 사유 |
|--------|--------|------|------|------|
| users | UK | email | UNIQUE | 로그인 |
| orders | idx | user_id | BTREE | 사용자별 조회 |
| orders | idx | product_id | BTREE | V15에서 추가 |
| payments | idx | order_id | BTREE | 주문별 조회 |
| payments | UK | idempotency_key | UNIQUE | 멱등성 |
| refunds | UK | (payment_id, idempotency_key) | UNIQUE | 환불 멱등성 |
| settlements | idx | payment_id | BTREE | 결제별 정산 |
| settlements | idx | status | BTREE | 배치 조회 |
| ecommerce_categories | UK | slug | UNIQUE | URL 식별 |
| coupons | UK | code | UNIQUE | 쿠폰 코드 |

---

> **본 문서는 Lemuel 전자상거래 주문·결제·정산 통합 시스템의 데이터 모델링/ERD 문서입니다.**
