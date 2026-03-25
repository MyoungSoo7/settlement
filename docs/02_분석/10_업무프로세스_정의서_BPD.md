# 업무 프로세스 정의서 (BPD: Business Process Definition)

| 항목 | 내용 |
|------|------|
| 문서번호 | LML-BPD-001 |
| 버전 | 1.1 |
| 작성일 | 2026-03-25 |
| 작성자 | AA (Application Architect) |
| 승인자 | PM |
| 문서상태 | 개선 |
| 기밀등급 | 대외비 |

---

## 버전 이력

| 버전 | 작성일 | 작성자 | 변경 내용 |
|------|--------|--------|-----------|
| 0.1 | 2026-03-25 | AA | 초안 작성 |
| 1.0 | 2026-03-25 | AA | 최종 확정 |
| 1.1 | 2026-03-25 | AA | 실제 구현 기반 상태 코드 반영 |

---

## 목차

1. [개요](#1-개요)
2. [현행(AS-IS) 업무 프로세스](#2-현행as-is-업무-프로세스)
3. [개선(TO-BE) 업무 프로세스](#3-개선to-be-업무-프로세스)
4. [프로세스 비교(GAP 분석)](#4-프로세스-비교gap-분석)

---

## 1. 개요

### 1.1 목적
현행 및 개선 업무 프로세스를 정의하여 시스템 구현 방향을 명확히 한다.

### 1.2 대상 프로세스

| No | 프로세스명 | 도메인 |
|----|-----------|--------|
| BP-001 | 주문 처리 프로세스 | 주문 |
| BP-002 | 결제 처리 프로세스 | 결제 |
| BP-003 | 환불 처리 프로세스 | 주문/결제/정산 |
| BP-004 | 정산 처리 프로세스 | 정산 |
| BP-005 | 상품 관리 프로세스 | 상품 |
| BP-006 | 쿠폰 관리 프로세스 | 쿠폰 |

---

## 2. 현행(AS-IS) 업무 프로세스

### 2.1 BP-001: 주문 처리 (AS-IS)

| 단계 | 수행자 | 활동 | 시스템 | 비고 |
|------|--------|------|--------|------|
| 1 | 구매자 | 상품 선택 및 장바구니 담기 | 웹 | |
| 2 | 구매자 | 주문 요청 | 웹 | |
| 3 | 시스템 | 재고 확인 | WAS | 수동 확인 병행 |
| 4 | 시스템 | 주문 생성 | WAS/DB | |
| 5 | 구매자 | 결제 진행 | PG | |
| 6 | 관리자 | 주문 확인 및 발송 | 수작업 | |

### 2.2 BP-004: 정산 처리 (AS-IS)

| 단계 | 수행자 | 활동 | 시스템 | 비고 |
|------|--------|------|--------|------|
| 1 | 관리자 | 결제 내역 엑셀 다운로드 | PG 관리자 | 매일 오전 |
| 2 | 관리자 | 판매자별 분류 | 엑셀 | 수작업 |
| 3 | 관리자 | 수수료 계산 | 엑셀 | 수작업 |
| 4 | 관리자 | 정산 금액 산출 | 엑셀 | 수작업 |
| 5 | 관리자 | 이체 요청 | 은행 | 수작업 |
| 6 | 관리자 | 정산 완료 확인 | 엑셀 | 수작업 |

---

## 3. 개선(TO-BE) 업무 프로세스

### 3.1 BP-001: 주문 처리 (TO-BE)

| 단계 | 수행자 | 활동 | 시스템 컴포넌트 | 비고 |
|------|--------|------|----------------|------|
| 1 | 구매자 | 상품 검색 및 장바구니 | React SPA (ProductPage, CartPage) | CartContext 사용 |
| 2 | 구매자 | 주문 생성 요청 | React → POST /orders | OrderCreateRequest |
| 3 | 시스템 | 재고 확인 및 주문 생성 | CreateOrderService | 상태: CREATED |
| 4 | 시스템 | 쿠폰 적용 (선택) | CouponService.validateAndUse() | CouponType: PERCENTAGE/FIXED_AMOUNT |
| 5 | 구매자 | Toss 결제 진행 | TossPaymentSuccess.tsx | Toss Payments SDK |
| 6 | 시스템 | 결제 승인 및 주문 확정 | TossPaymentService | POST /payments/toss/confirm |
| 7 | 시스템 | 정산 데이터 자동 생성 | CreateDailySettlementsService | 배치 02:00 |

**주문 상태 흐름 (OrderStatus enum):**

```
CREATED → CONFIRMED → (정상 완료)
   ↓          ↓
CANCELLED   REFUNDED
```

> **참고**: 실제 OrderStatus enum 값은 `CREATED`, `CONFIRMED`, `CANCELLED`, `REFUNDED` 4가지이다.

### 3.2 BP-002: 결제 처리 (TO-BE)

| 단계 | 수행자 | 활동 | 시스템 컴포넌트 | 비고 |
|------|--------|------|----------------|------|
| 1 | 시스템 | 결제 요청 생성 | CreatePaymentUseCase | POST /payments, 상태: READY |
| 2 | 구매자 | Toss 결제 페이지에서 결제 | Toss Payments SDK | 클라이언트 사이드 |
| 3 | 시스템 | 결제 승인(Authorize) | AuthorizePaymentUseCase | PATCH /payments/{id}/authorize, 상태: AUTHORIZED |
| 4 | 시스템 | 결제 매입(Capture) | CapturePaymentUseCase | PATCH /payments/{id}/capture, 상태: CAPTURED |
| 5 | 시스템 | Toss 최종 확인 | TossPaymentService | POST /payments/toss/confirm |
| 6 | 시스템 | 주문 상태 업데이트 | ChangeOrderStatusService | CREATED → CONFIRMED |

**결제 상태 흐름 (PaymentStatus enum):**

```
READY → AUTHORIZED → CAPTURED → (정상 완료)
  ↓         ↓           ↓
CANCELED  CANCELED   REFUNDED / PARTIALLY_REFUNDED
```

> **참고**: 실제 PaymentStatus enum 값은 `READY`, `AUTHORIZED`, `CAPTURED`, `REFUNDED`, `PARTIALLY_REFUNDED`, `CANCELED` 6가지이다.

### 3.3 BP-003: 환불 처리 (TO-BE)

| 단계 | 수행자 | 활동 | 시스템 컴포넌트 | 비고 |
|------|--------|------|----------------|------|
| 1 | 구매자 | 환불 요청 | React → PATCH /payments/{id}/refund | Idempotency-Key 헤더 필수 |
| 2 | 시스템 | Idempotency-Key 중복 확인 | RefundPaymentUseCase | refunds 테이블 조회 |
| 3 | 시스템 | 비관적 락 획득 | JPA PESSIMISTIC_WRITE | 이중 환불 방지 |
| 4 | 시스템 | Toss PG 환불 요청 | PgClientPort | Toss Payments API |
| 5 | 시스템 | refunded_amount 갱신 | PaymentJpaEntity | 누적 환불 금액 추적 |
| 6 | 시스템 | 결제 상태 변경 | PaymentJpaRepository | REFUNDED/PARTIALLY_REFUNDED |
| 7 | 시스템 | 주문 상태 변경 | ChangeOrderStatusService | REFUNDED |
| 8 | 시스템 | 정산 조정 생성 | AdjustSettlementForRefundService | settlement_adjustments 테이블 |

**환불 데이터 모델:**
- `refunds` 테이블: idempotency_key (UNIQUE per payment), 상태 REQUESTED/COMPLETED/FAILED
- `settlement_adjustments` 테이블: 환불 발생 시 역정산 기록

### 3.4 BP-004: 정산 처리 (TO-BE)

| 단계 | 수행자 | 활동 | 시스템 컴포넌트 | 비고 |
|------|--------|------|----------------|------|
| 1 | 배치 | CAPTURED 결제 조회 | CreateSettlementsTasklet | 매일 02:00, Spring Batch |
| 2 | 배치 | 수수료 3% 계산 | Settlement.java | 하드코딩 3% (amount × 0.03) |
| 3 | 배치 | PENDING 정산 레코드 생성 | CreateDailySettlementsService | net = amount - fee |
| 4 | 배치 | PENDING → CONFIRMED 확정 | ConfirmSettlementsTasklet | 매일 03:00 |
| 5 | 배치 | 환불 건 정산 조정 확정 | AdjustSettlementForRefundService | 매일 03:10 |
| 6 | 관리자 | 정산 내역 검색/확인 | SettlementSearchController | Elasticsearch 검색 |
| 7 | 관리자 | PDF 정산 리포트 | GenerateSettlementPdfService | iText 8 + GhostscriptService |
| 8 | 시스템 | Slack 알림 | SlackWebhook | 배치 완료/실패 알림 |

**정산 상태 흐름 (SettlementStatus enum):**

```
REQUESTED → PENDING → CONFIRMED → (정산 완료)
                ↓          ↓
             FAILED     CANCELED
```

> **참고**: 실제 SettlementStatus enum 값은 `REQUESTED`, `PENDING`, `CONFIRMED`, `FAILED`, `CANCELED` 5가지이다.

**정산 배치 모니터링 (Prometheus Metrics):**

| 메트릭 | 설명 |
|--------|------|
| settlement.batch.creation.duration | 정산 생성 배치 소요 시간 |
| settlement.batch.confirmation.duration | 정산 확정 배치 소요 시간 |
| settlement.batch.adjustment.duration | 조정 확정 배치 소요 시간 |
| refund.processing.duration | 환불 처리 소요 시간 (p50, p95, p99) |

### 3.5 BP-005: 상품 관리 (TO-BE)

| 단계 | 수행자 | 활동 | 시스템 컴포넌트 | 비고 |
|------|--------|------|----------------|------|
| 1 | 관리자 | 상품 정보 입력 | React (CreateProductForm.tsx) | |
| 2 | 시스템 | 상품 등록 | CreateProductService | POST /api/products |
| 3 | 시스템 | 이미지 업로드 | ProductImageService + FileStorageService | POST /product-images |
| 4 | 시스템 | 카테고리 연결 | EcommerceCategoryService | 다계층 구조, Slug 생성 |
| 5 | 시스템 | 태그 관리 | TagService | POST /tags |
| 6 | 관리자 | 상품 상태 변경 | ManageProductStatusService | ACTIVE/INACTIVE/OUT_OF_STOCK/DISCONTINUED |

**상품 상태 (ProductStatus enum):**

| 코드 | 설명 |
|------|------|
| ACTIVE | 판매 중 |
| INACTIVE | 비활성 |
| OUT_OF_STOCK | 품절 |
| DISCONTINUED | 판매 중단 |

### 3.6 BP-006: 쿠폰 관리 (TO-BE)

| 단계 | 수행자 | 활동 | 시스템 컴포넌트 | 비고 |
|------|--------|------|----------------|------|
| 1 | 관리자 | 쿠폰 생성 | CouponService | POST /coupons |
| 2 | 구매자 | 쿠폰 유효성 검증 | CouponService.validate() | GET /coupons/{code}/validate |
| 3 | 구매자 | 쿠폰 적용 (주문 시) | CouponService.use() | POST /coupons/{code}/use |
| 4 | 시스템 | 사용 내역 기록 | CouponUsageJpaEntity | coupon_usages 테이블 |

**쿠폰 유형 (CouponType enum):**

| 코드 | 설명 | 계산 |
|------|------|------|
| PERCENTAGE | 정률 할인 | amount × (discount_value / 100), max_discount 상한 |
| FIXED_AMOUNT | 정액 할인 | discount_value (min_order_amount 이상 시) |

---

## 4. 프로세스 비교 (GAP 분석)

| 프로세스 | AS-IS | TO-BE | 개선 효과 |
|---------|-------|-------|-----------|
| 주문 | 수동 확인 병행 | CreateOrderService 자동화 | 처리 속도 향상 |
| 결제 | 단일 PG, 동시성 미제어 | Toss PG, Idempotency-Key + PESSIMISTIC_WRITE | 이중 환불 방지 |
| 환불 | 수작업 환불 | 자동 환불 + settlement_adjustments 역정산 | 오류 감소 |
| 정산 | 엑셀 수작업 | Spring Batch 자동화 (수수료 3% 자동 계산) | 90% 시간 단축 |
| 정산 모니터링 | 없음 | Prometheus 메트릭 + Slack 알림 | 실시간 이상 감지 |
| 정산 리포트 | 엑셀 | iText8 PDF 자동 생성 | 보고 자동화 |
| 검색 | DB 직접 쿼리 | Elasticsearch (Elastic Cloud) | 검색 성능/한글 품질 |
| 인증 | 세션 기반 | JWT (jjwt 0.12.5) | 확장성 확보 |
| 캐싱 | 없음 | Caffeine Cache (500건, 600초) | 응답 속도 향상 |

---

> **본 문서는 Lemuel 전자상거래 주문·결제·정산 통합 시스템의 업무 프로세스 정의서입니다.**
