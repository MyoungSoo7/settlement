# Settlement QueryDSL Query Module Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the raw JDBC-based `SettlementSearchJdbcRepository` with a type-safe QueryDSL query module that provides settlement summary, payment/refund aggregation, approval tracking, cursor-based pagination, and financial reconciliation queries — all using DTO projections (no entity returns).

**Architecture:** Read-side QueryDSL custom repository implementing hexagonal outbound port. Uses `JPAQueryFactory` with `Projections.constructor()` for DTO-based results. Cursor-based pagination using `(settlement_date, id)` composite cursor for stable, index-friendly paging over millions of rows. Separate query methods for summary aggregation, detail listing, and reconciliation — each optimized for its own access pattern.

**Tech Stack:** Java 21, Spring Boot 3.5.10, QueryDSL 5.0.0 (Jakarta), JPA/Hibernate 6, PostgreSQL, Flyway

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `settlement/adapter/out/persistence/querydsl/SettlementQueryRepository.java` | Port interface for QueryDSL read operations |
| `settlement/adapter/out/persistence/querydsl/SettlementQueryRepositoryImpl.java` | QueryDSL implementation with JPAQueryFactory |
| `settlement/adapter/out/persistence/querydsl/dto/SettlementSummaryDto.java` | Daily/monthly aggregation projection |
| `settlement/adapter/out/persistence/querydsl/dto/SettlementDetailDto.java` | Detail listing with payment/order/product joins |
| `settlement/adapter/out/persistence/querydsl/dto/PaymentRefundAggregationDto.java` | Payment + refund aggregation projection |
| `settlement/adapter/out/persistence/querydsl/dto/ApprovalStatusDto.java` | Approval status tracking projection |
| `settlement/adapter/out/persistence/querydsl/dto/SettlementReconciliationDto.java` | Reconciliation mismatch detection projection |
| `settlement/adapter/out/persistence/querydsl/dto/SettlementCursorPageResponse.java` | Cursor-based pagination response wrapper |
| `settlement/adapter/out/persistence/querydsl/dto/SettlementSearchCondition.java` | Type-safe search filter condition object |
| `settlement/application/port/out/QuerySettlementPort.java` | Hexagonal outbound port for read queries |
| `settlement/application/service/SettlementQueryService.java` | Application service wiring port to adapter |
| `settlement/adapter/in/web/SettlementQueryController.java` | REST controller for query endpoints |
| `src/main/resources/db/migration/V22__add_settlement_query_indexes.sql` | Composite indexes for QueryDSL queries |
| `src/test/java/.../querydsl/SettlementQueryRepositoryImplTest.java` | Integration test |

### Modified Files

| File | Change |
|------|--------|
| `settlement/adapter/out/persistence/SettlementJpaEntity.java` | Add `refundedAmount` column (missing from entity but exists in DB per V4 migration) |

---

## Chunk 1: Foundation — DTOs, Condition, and Flyway Migration

### Task 1: Add missing `refundedAmount` to SettlementJpaEntity

The V4 migration added `refunded_amount` to the settlements table via `adjustForRefund()` in the domain, but the JPA entity is missing this column. QueryDSL Q-classes derive from entity fields, so this must be present.

**Files:**
- Modify: `settlement/adapter/out/persistence/SettlementJpaEntity.java:36`

- [ ] **Step 1: Add refundedAmount field to SettlementJpaEntity**

After the `paymentAmount` field (line 36), add:

```java
@Column(name = "refunded_amount", nullable = false, precision = 10, scale = 2)
private BigDecimal refundedAmount = BigDecimal.ZERO;
```

- [ ] **Step 2: Verify Q-class generation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESS, Q-class at `build/generated/querydsl/.../QSettlementJpaEntity.java` now includes `refundedAmount`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/SettlementJpaEntity.java
git commit -m "fix: add missing refundedAmount field to SettlementJpaEntity

The V4 migration added refunded_amount to settlements table but
the JPA entity was missing this column mapping."
```

### Task 2: Create search condition DTO

**Files:**
- Create: `settlement/adapter/out/persistence/querydsl/dto/SettlementSearchCondition.java`

- [ ] **Step 1: Create SettlementSearchCondition**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class SettlementSearchCondition {
    private final String status;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final Long userId;
    private final String ordererName;   // email LIKE search
    private final String productName;   // product name LIKE search
    private final Boolean isRefunded;
    private final Long cursorId;        // cursor-based pagination: last seen settlement ID
    private final LocalDate cursorDate; // cursor-based pagination: last seen settlement date
    private final int size;             // page size (default 20, max 100)
    private final String sortBy;        // createdAt, amount, settlementDate, settlementId
    private final String sortDirection; // ASC or DESC
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/dto/SettlementSearchCondition.java
git commit -m "feat: add SettlementSearchCondition for type-safe query filtering"
```

### Task 3: Create projection DTOs

**Files:**
- Create: `settlement/adapter/out/persistence/querydsl/dto/SettlementSummaryDto.java`
- Create: `settlement/adapter/out/persistence/querydsl/dto/SettlementDetailDto.java`
- Create: `settlement/adapter/out/persistence/querydsl/dto/PaymentRefundAggregationDto.java`
- Create: `settlement/adapter/out/persistence/querydsl/dto/ApprovalStatusDto.java`
- Create: `settlement/adapter/out/persistence/querydsl/dto/SettlementReconciliationDto.java`
- Create: `settlement/adapter/out/persistence/querydsl/dto/SettlementCursorPageResponse.java`

- [ ] **Step 1: Create SettlementSummaryDto**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 일별/월별 정산 요약 집계 DTO
 * QueryDSL Projections.constructor()로 직접 매핑
 */
@Getter
@AllArgsConstructor
public class SettlementSummaryDto {
    private final LocalDate settlementDate;
    private final long totalCount;
    private final BigDecimal totalPaymentAmount;
    private final BigDecimal totalRefundedAmount;
    private final BigDecimal totalCommission;
    private final BigDecimal totalNetAmount;
    private final long doneCount;
    private final long failedCount;
    private final long canceledCount;
}
```

- [ ] **Step 2: Create SettlementDetailDto**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 정산 상세 조회 DTO (주문/결제/상품 JOIN)
 * N+1 방지: 단일 쿼리로 모든 필요한 필드를 프로젝션
 */
@Getter
@AllArgsConstructor
public class SettlementDetailDto {
    // Settlement
    private final Long settlementId;
    private final BigDecimal paymentAmount;
    private final BigDecimal refundedAmount;
    private final BigDecimal commission;
    private final BigDecimal netAmount;
    private final String status;
    private final LocalDate settlementDate;
    private final LocalDateTime confirmedAt;
    private final LocalDateTime createdAt;

    // Order
    private final Long orderId;
    private final Long userId;

    // Payment
    private final Long paymentId;
    private final String paymentMethod;
    private final String paymentStatus;

    // User
    private final String ordererEmail;

    // Product
    private final String productName;

    // Derived
    private final boolean isRefunded;
}
```

- [ ] **Step 3: Create PaymentRefundAggregationDto**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 결제/환불 집계 DTO
 * 기간별 결제 총액, 환불 총액, 순 정산액을 집계
 */
@Getter
@AllArgsConstructor
public class PaymentRefundAggregationDto {
    private final long totalPaymentCount;
    private final BigDecimal totalPaymentAmount;
    private final long refundedPaymentCount;
    private final BigDecimal totalRefundedAmount;
    private final BigDecimal totalCommission;
    private final BigDecimal totalNetAmount;
    private final BigDecimal refundRate;  // refundedAmount / paymentAmount * 100
}
```

- [ ] **Step 4: Create ApprovalStatusDto**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 승인 상태 추적 DTO
 * WAITING_APPROVAL, APPROVED, REJECTED 상태의 정산 건을 추적
 */
@Getter
@AllArgsConstructor
public class ApprovalStatusDto {
    private final Long settlementId;
    private final Long orderId;
    private final Long paymentId;
    private final BigDecimal netAmount;
    private final String status;
    private final LocalDate settlementDate;
    private final String ordererEmail;
}
```

- [ ] **Step 5: Create SettlementReconciliationDto**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 정산 대사 (Reconciliation) DTO
 * 결제 금액과 정산 금액 간 불일치를 탐지
 */
@Getter
@AllArgsConstructor
public class SettlementReconciliationDto {
    private final Long settlementId;
    private final Long paymentId;
    private final BigDecimal paymentAmount;          // payments.amount
    private final BigDecimal settlementPaymentAmount; // settlements.payment_amount
    private final BigDecimal paymentRefundedAmount;   // payments.refunded_amount
    private final BigDecimal settlementRefundedAmount;// settlements.refunded_amount (from adjustForRefund)
    private final BigDecimal amountDifference;        // paymentAmount - settlementPaymentAmount
    private final String paymentStatus;
    private final String settlementStatus;
}
```

- [ ] **Step 6: Create SettlementCursorPageResponse**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * Cursor 기반 페이지네이션 응답
 *
 * WHY cursor-based:
 * - offset pagination은 OFFSET이 커질수록 성능 저하 (full scan)
 * - 수백만 건에서 cursor(settlement_date, id) 기반이 O(log n)
 * - 실시간 데이터 삽입 시 페이지 밀림 현상 없음
 */
@Getter
@AllArgsConstructor
public class SettlementCursorPageResponse<T> {
    private final List<T> items;
    private final int size;
    private final boolean hasNext;
    private final Long nextCursorId;
    private final LocalDate nextCursorDate;
}
```

- [ ] **Step 7: Commit all DTOs**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/dto/
git commit -m "feat: add QueryDSL projection DTOs for settlement queries

Includes: SettlementSummaryDto, SettlementDetailDto,
PaymentRefundAggregationDto, ApprovalStatusDto,
SettlementReconciliationDto, SettlementCursorPageResponse"
```

### Task 4: Create Flyway migration for composite indexes

**Files:**
- Create: `src/main/resources/db/migration/V22__add_settlement_query_indexes.sql`

- [ ] **Step 1: Create migration**

```sql
-- V22: QueryDSL 쿼리 최적화를 위한 복합 인덱스
-- 기존 인덱스: idx_settlements_settlement_date, idx_settlements_status, idx_settlements_date_status

-- 1. Cursor 기반 페이지네이션용 복합 인덱스
-- WHERE settlement_date <= ? AND id < ? ORDER BY settlement_date DESC, id DESC
-- 커서 기반 페이징에서 (settlement_date, id) 복합 정렬에 사용
CREATE INDEX IF NOT EXISTS idx_settlements_date_id_desc
ON settlements(settlement_date DESC, id DESC);

-- 2. 월별 집계용 인덱스 (status 포함)
-- GROUP BY settlement_date WHERE status = ? 쿼리에서 Index-Only Scan 가능
CREATE INDEX IF NOT EXISTS idx_settlements_date_status_amounts
ON settlements(settlement_date, status)
INCLUDE (payment_amount, refunded_amount, commission, net_amount);

-- 3. 대사(Reconciliation) 쿼리용: payment_id로 settlement 조회 시 금액 포함
-- settlements와 payments를 payment_id로 JOIN할 때 covering index
CREATE INDEX IF NOT EXISTS idx_settlements_payment_id_amounts
ON settlements(payment_id)
INCLUDE (payment_amount, refunded_amount, net_amount, status);

-- 4. 승인 상태 추적용: 승인 관련 상태만 필터링
CREATE INDEX IF NOT EXISTS idx_settlements_approval_status
ON settlements(status)
WHERE status IN ('WAITING_APPROVAL', 'APPROVED', 'REJECTED');

-- 5. payments 테이블: captured_at 기반 조회 최적화 (정산 생성 배치에서 사용)
CREATE INDEX IF NOT EXISTS idx_payments_captured_status_amount
ON payments(captured_at, status)
INCLUDE (amount, refunded_amount, order_id);
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V22__add_settlement_query_indexes.sql
git commit -m "feat: add composite indexes for QueryDSL settlement queries

Covering indexes for cursor pagination, monthly aggregation,
reconciliation, and approval status tracking."
```

---

## Chunk 2: QueryDSL Repository Implementation

### Task 5: Create hexagonal outbound port

**Files:**
- Create: `settlement/application/port/out/QuerySettlementPort.java`

- [ ] **Step 1: Create QuerySettlementPort**

```java
package github.lms.lemuel.settlement.application.port.out;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 조회 전용 Outbound Port (Read Model)
 * Write model(LoadSettlementPort)과 분리하여 CQRS 원칙 적용
 */
public interface QuerySettlementPort {

    /** 일별 정산 요약 (기간 내 일별 집계) */
    List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate);

    /** 월별 정산 요약 (기간 내 월별 집계) */
    List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate);

    /** 정산 상세 목록 (cursor-based pagination) */
    SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition);

    /** 결제/환불 집계 */
    PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate);

    /** 승인 상태 추적 */
    SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(
            String status, int size, Long cursorId);

    /** 대사 불일치 탐지 */
    List<SettlementReconciliationDto> findReconciliationMismatches(LocalDate startDate, LocalDate endDate);

    /** 감사 쿼리: 특정 결제 건의 정산 이력 추적 */
    List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/application/port/out/QuerySettlementPort.java
git commit -m "feat: add QuerySettlementPort outbound port for read queries"
```

### Task 6: Create QueryDSL repository interface

**Files:**
- Create: `settlement/adapter/out/persistence/querydsl/SettlementQueryRepository.java`

- [ ] **Step 1: Create interface**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * QueryDSL 기반 정산 조회 Repository
 * Spring Data JPA Custom Repository 패턴
 */
public interface SettlementQueryRepository {

    List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate);

    List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate);

    SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition);

    PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate);

    SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(
            String status, int size, Long cursorId);

    List<SettlementReconciliationDto> findReconciliationMismatches(
            LocalDate startDate, LocalDate endDate);

    List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/SettlementQueryRepository.java
git commit -m "feat: add SettlementQueryRepository interface"
```

### Task 7: Implement QueryDSL repository — core queries

This is the main implementation. Uses `JPAQueryFactory` with Q-classes from all relevant entities.

**Files:**
- Create: `settlement/adapter/out/persistence/querydsl/SettlementQueryRepositoryImpl.java`

- [ ] **Step 1: Create SettlementQueryRepositoryImpl with daily/monthly summary**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import github.lms.lemuel.order.adapter.out.persistence.QOrderJpaEntity;
import github.lms.lemuel.payment.adapter.out.persistence.QPaymentJpaEntity;
import github.lms.lemuel.product.adapter.out.persistence.QProductJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.QSettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.user.adapter.out.persistence.QUserJpaEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class SettlementQueryRepositoryImpl implements SettlementQueryRepository {

    private final JPAQueryFactory queryFactory;

    private static final QSettlementJpaEntity settlement = QSettlementJpaEntity.settlementJpaEntity;
    private static final QPaymentJpaEntity payment = QPaymentJpaEntity.paymentJpaEntity;
    private static final QOrderJpaEntity order = QOrderJpaEntity.orderJpaEntity;
    private static final QUserJpaEntity user = QUserJpaEntity.userJpaEntity;
    private static final QProductJpaEntity product = QProductJpaEntity.productJpaEntity;

    /**
     * 일별 정산 요약
     *
     * SQL: SELECT settlement_date, COUNT(*), SUM(payment_amount), ...
     *      FROM settlements
     *      WHERE settlement_date BETWEEN ? AND ?
     *      GROUP BY settlement_date
     *      ORDER BY settlement_date
     *
     * INDEX: idx_settlements_date_status_amounts (covering index)
     * 성능: GROUP BY settlement_date는 B-Tree 인덱스 순서와 일치하므로 Index-Only Scan 가능
     */
    @Override
    public List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate) {
        return queryFactory
                .select(Projections.constructor(SettlementSummaryDto.class,
                        settlement.settlementDate,
                        settlement.count(),
                        settlement.paymentAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.refundedAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.commission.sum().coalesce(BigDecimal.ZERO),
                        settlement.netAmount.sum().coalesce(BigDecimal.ZERO),
                        statusCount("DONE"),
                        statusCount("FAILED"),
                        statusCount("CANCELED")
                ))
                .from(settlement)
                .where(
                        settlement.settlementDate.goe(startDate),
                        settlement.settlementDate.loe(endDate)
                )
                .groupBy(settlement.settlementDate)
                .orderBy(settlement.settlementDate.asc())
                .fetch();
    }

    /**
     * 월별 정산 요약
     *
     * PostgreSQL의 date_trunc('month', settlement_date)로 월별 그룹핑
     * QueryDSL에서 DB 함수를 사용하기 위해 Expressions.dateTemplate 사용
     *
     * INDEX: idx_settlements_date_status_amounts
     */
    @Override
    public List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate) {
        var monthExpr = Expressions.dateTemplate(
                LocalDate.class,
                "CAST(date_trunc('month', {0}) AS date)",
                settlement.settlementDate
        );

        return queryFactory
                .select(Projections.constructor(SettlementSummaryDto.class,
                        monthExpr,
                        settlement.count(),
                        settlement.paymentAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.refundedAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.commission.sum().coalesce(BigDecimal.ZERO),
                        settlement.netAmount.sum().coalesce(BigDecimal.ZERO),
                        statusCount("DONE"),
                        statusCount("FAILED"),
                        statusCount("CANCELED")
                ))
                .from(settlement)
                .where(
                        settlement.settlementDate.goe(startDate),
                        settlement.settlementDate.loe(endDate)
                )
                .groupBy(monthExpr)
                .orderBy(monthExpr.asc())
                .fetch();
    }

    /**
     * 정산 상세 검색 (Cursor 기반 페이지네이션)
     *
     * WHY cursor-based:
     * - OFFSET 방식은 OFFSET 10000일 때 10000건을 읽고 버려야 하므로 O(n)
     * - Cursor 방식은 WHERE (date, id) < (cursor_date, cursor_id)로 즉시 인덱스 탐색 O(log n)
     * - 수백만 건 테이블에서 2페이지든 1000페이지든 동일한 성능
     *
     * INDEX: idx_settlements_date_id_desc
     * JOIN: settlement → payment, order → user, order → product (LEFT JOIN)
     * N+1 방지: 단일 쿼리로 모든 필드를 DTO 프로젝션
     */
    @Override
    public SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition) {
        int fetchSize = Math.min(condition.getSize() > 0 ? condition.getSize() : 20, 100);

        BooleanBuilder where = buildSearchWhere(condition);

        // Cursor 조건 적용
        if (condition.getCursorId() != null && condition.getCursorDate() != null) {
            where.and(cursorCondition(condition.getCursorDate(), condition.getCursorId(),
                    "DESC".equalsIgnoreCase(condition.getSortDirection())));
        }

        List<SettlementDetailDto> items = queryFactory
                .select(Projections.constructor(SettlementDetailDto.class,
                        settlement.id,
                        settlement.paymentAmount,
                        settlement.refundedAmount,
                        settlement.commission,
                        settlement.netAmount,
                        settlement.status,
                        settlement.settlementDate,
                        settlement.confirmedAt,
                        settlement.createdAt,
                        order.id,
                        order.userId,
                        payment.id,
                        payment.paymentMethod,
                        payment.status,
                        user.email,
                        product.name.coalesce(""),
                        settlement.refundedAmount.gt(BigDecimal.ZERO)
                ))
                .from(settlement)
                .join(payment).on(settlement.paymentId.eq(payment.id))
                .join(order).on(settlement.orderId.eq(order.id))
                .join(user).on(order.userId.eq(user.id))
                .leftJoin(product).on(order.productId.eq(product.id))
                .where(where)
                .orderBy(buildOrderSpecifier(condition))
                .limit(fetchSize + 1) // +1 to detect hasNext
                .fetch();

        boolean hasNext = items.size() > fetchSize;
        if (hasNext) {
            items = items.subList(0, fetchSize);
        }

        Long nextCursorId = null;
        LocalDate nextCursorDate = null;
        if (hasNext && !items.isEmpty()) {
            SettlementDetailDto last = items.get(items.size() - 1);
            nextCursorId = last.getSettlementId();
            nextCursorDate = last.getSettlementDate();
        }

        return new SettlementCursorPageResponse<>(items, fetchSize, hasNext, nextCursorId, nextCursorDate);
    }

    /**
     * 결제/환불 집계
     *
     * 기간 내 전체 결제/환불 통계를 단일 쿼리로 반환
     * settlements 테이블만 사용 (JOIN 불필요, 성능 최적)
     */
    @Override
    public PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate) {
        var result = queryFactory
                .select(
                        settlement.count(),
                        settlement.paymentAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.refundedAmount.gt(BigDecimal.ZERO).count(),
                        settlement.refundedAmount.sum().coalesce(BigDecimal.ZERO),
                        settlement.commission.sum().coalesce(BigDecimal.ZERO),
                        settlement.netAmount.sum().coalesce(BigDecimal.ZERO)
                )
                .from(settlement)
                .where(
                        settlement.settlementDate.goe(startDate),
                        settlement.settlementDate.loe(endDate)
                )
                .fetchOne();

        if (result == null) {
            return new PaymentRefundAggregationDto(0, BigDecimal.ZERO, 0,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        BigDecimal totalPayment = result.get(1, BigDecimal.class);
        BigDecimal totalRefunded = result.get(3, BigDecimal.class);
        BigDecimal refundRate = BigDecimal.ZERO;
        if (totalPayment.compareTo(BigDecimal.ZERO) > 0) {
            refundRate = totalRefunded.multiply(new BigDecimal("100"))
                    .divide(totalPayment, 2, RoundingMode.HALF_UP);
        }

        return new PaymentRefundAggregationDto(
                result.get(0, Long.class),
                totalPayment,
                result.get(2, Long.class),
                totalRefunded,
                result.get(4, BigDecimal.class),
                result.get(5, BigDecimal.class),
                refundRate
        );
    }

    /**
     * 승인 상태 추적 (Cursor 기반)
     *
     * WAITING_APPROVAL, APPROVED, REJECTED 상태만 필터링
     * INDEX: idx_settlements_approval_status (partial index)
     */
    @Override
    public SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(
            String status, int size, Long cursorId) {
        int fetchSize = Math.min(size > 0 ? size : 20, 100);

        BooleanBuilder where = new BooleanBuilder();
        if (status != null && !status.isBlank()) {
            where.and(settlement.status.eq(status));
        } else {
            where.and(settlement.status.in("WAITING_APPROVAL", "APPROVED", "REJECTED"));
        }

        if (cursorId != null) {
            where.and(settlement.id.lt(cursorId));
        }

        List<ApprovalStatusDto> items = queryFactory
                .select(Projections.constructor(ApprovalStatusDto.class,
                        settlement.id,
                        settlement.orderId,
                        settlement.paymentId,
                        settlement.netAmount,
                        settlement.status,
                        settlement.settlementDate,
                        user.email
                ))
                .from(settlement)
                .join(order).on(settlement.orderId.eq(order.id))
                .join(user).on(order.userId.eq(user.id))
                .where(where)
                .orderBy(settlement.id.desc())
                .limit(fetchSize + 1)
                .fetch();

        boolean hasNext = items.size() > fetchSize;
        if (hasNext) {
            items = items.subList(0, fetchSize);
        }

        Long nextCursorId = null;
        if (hasNext && !items.isEmpty()) {
            nextCursorId = items.get(items.size() - 1).getSettlementId();
        }

        return new SettlementCursorPageResponse<>(items, fetchSize, hasNext, nextCursorId, null);
    }

    /**
     * 대사 불일치 탐지 (Reconciliation)
     *
     * WHY:
     * - 결제 시스템과 정산 시스템 간 금액 불일치는 치명적인 재무 사고
     * - payments.amount ≠ settlements.payment_amount 이면 정산 생성 시점 버그
     * - payments.refunded_amount ≠ settlements.refunded_amount 이면 환불 반영 누락
     *
     * INDEX: idx_settlements_payment_id_amounts (covering index for settlement side)
     */
    @Override
    public List<SettlementReconciliationDto> findReconciliationMismatches(
            LocalDate startDate, LocalDate endDate) {
        return queryFactory
                .select(Projections.constructor(SettlementReconciliationDto.class,
                        settlement.id,
                        settlement.paymentId,
                        payment.amount,
                        settlement.paymentAmount,
                        payment.refundedAmount,
                        settlement.refundedAmount,
                        payment.amount.subtract(settlement.paymentAmount),
                        payment.status,
                        settlement.status
                ))
                .from(settlement)
                .join(payment).on(settlement.paymentId.eq(payment.id))
                .where(
                        settlement.settlementDate.goe(startDate),
                        settlement.settlementDate.loe(endDate),
                        // 금액 불일치 조건: payment_amount 또는 refunded_amount가 다른 건
                        payment.amount.ne(settlement.paymentAmount)
                                .or(payment.refundedAmount.ne(settlement.refundedAmount))
                )
                .orderBy(settlement.settlementDate.desc(), settlement.id.desc())
                .fetch();
    }

    /**
     * 감사 추적 (Audit Trail)
     *
     * 특정 payment_id에 연결된 정산 건의 전체 이력
     * 결제 → 정산 → 환불 조정까지 추적 가능
     */
    @Override
    public List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId) {
        return queryFactory
                .select(Projections.constructor(SettlementDetailDto.class,
                        settlement.id,
                        settlement.paymentAmount,
                        settlement.refundedAmount,
                        settlement.commission,
                        settlement.netAmount,
                        settlement.status,
                        settlement.settlementDate,
                        settlement.confirmedAt,
                        settlement.createdAt,
                        order.id,
                        order.userId,
                        payment.id,
                        payment.paymentMethod,
                        payment.status,
                        user.email,
                        product.name.coalesce(""),
                        settlement.refundedAmount.gt(BigDecimal.ZERO)
                ))
                .from(settlement)
                .join(payment).on(settlement.paymentId.eq(payment.id))
                .join(order).on(settlement.orderId.eq(order.id))
                .join(user).on(order.userId.eq(user.id))
                .leftJoin(product).on(order.productId.eq(product.id))
                .where(settlement.paymentId.eq(paymentId))
                .orderBy(settlement.createdAt.desc())
                .fetch();
    }

    // ========== Private Helper Methods ==========

    /**
     * 상태별 건수 카운트 (CASE WHEN ... THEN 1 ELSE 0 END 패턴)
     */
    private NumberExpression<Long> statusCount(String statusValue) {
        return Expressions.cases()
                .when(settlement.status.eq(statusValue)).then(1L)
                .otherwise(0L)
                .sum();
    }

    /**
     * 검색 조건 빌더
     *
     * BooleanBuilder 사용으로 동적 WHERE 절 구성
     * 각 조건은 null-safe — null이면 조건에서 제외됨
     */
    private BooleanBuilder buildSearchWhere(SettlementSearchCondition condition) {
        BooleanBuilder builder = new BooleanBuilder();

        if (condition.getStatus() != null && !condition.getStatus().isBlank()) {
            builder.and(settlement.status.eq(condition.getStatus()));
        }
        if (condition.getStartDate() != null) {
            builder.and(settlement.settlementDate.goe(condition.getStartDate()));
        }
        if (condition.getEndDate() != null) {
            builder.and(settlement.settlementDate.loe(condition.getEndDate()));
        }
        if (condition.getUserId() != null) {
            builder.and(order.userId.eq(condition.getUserId()));
        }
        if (condition.getOrdererName() != null && !condition.getOrdererName().isBlank()) {
            builder.and(user.email.containsIgnoreCase(condition.getOrdererName()));
        }
        if (condition.getProductName() != null && !condition.getProductName().isBlank()) {
            builder.and(product.name.containsIgnoreCase(condition.getProductName()));
        }
        if (Boolean.TRUE.equals(condition.getIsRefunded())) {
            builder.and(settlement.refundedAmount.gt(BigDecimal.ZERO));
        } else if (Boolean.FALSE.equals(condition.getIsRefunded())) {
            builder.and(settlement.refundedAmount.eq(BigDecimal.ZERO));
        }

        return builder;
    }

    /**
     * Cursor 조건 생성
     *
     * DESC 정렬: (date < cursorDate) OR (date = cursorDate AND id < cursorId)
     * ASC 정렬:  (date > cursorDate) OR (date = cursorDate AND id > cursorId)
     *
     * 이 패턴은 (date, id) 복합 인덱스를 최대한 활용함
     */
    private BooleanExpression cursorCondition(LocalDate cursorDate, Long cursorId, boolean isDesc) {
        if (isDesc) {
            return settlement.settlementDate.lt(cursorDate)
                    .or(settlement.settlementDate.eq(cursorDate)
                            .and(settlement.id.lt(cursorId)));
        }
        return settlement.settlementDate.gt(cursorDate)
                .or(settlement.settlementDate.eq(cursorDate)
                        .and(settlement.id.gt(cursorId)));
    }

    /**
     * 정렬 조건 생성
     */
    private OrderSpecifier<?>[] buildOrderSpecifier(SettlementSearchCondition condition) {
        boolean isAsc = "ASC".equalsIgnoreCase(condition.getSortDirection());

        return switch (condition.getSortBy() != null ? condition.getSortBy() : "settlementDate") {
            case "amount" -> isAsc
                    ? new OrderSpecifier[]{settlement.paymentAmount.asc(), settlement.id.asc()}
                    : new OrderSpecifier[]{settlement.paymentAmount.desc(), settlement.id.desc()};
            case "settlementId" -> isAsc
                    ? new OrderSpecifier[]{settlement.id.asc()}
                    : new OrderSpecifier[]{settlement.id.desc()};
            case "createdAt" -> isAsc
                    ? new OrderSpecifier[]{settlement.createdAt.asc(), settlement.id.asc()}
                    : new OrderSpecifier[]{settlement.createdAt.desc(), settlement.id.desc()};
            default -> isAsc  // settlementDate
                    ? new OrderSpecifier[]{settlement.settlementDate.asc(), settlement.id.asc()}
                    : new OrderSpecifier[]{settlement.settlementDate.desc(), settlement.id.desc()};
        };
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/SettlementQueryRepositoryImpl.java
git commit -m "feat: implement QueryDSL settlement query repository

Includes daily/monthly summary, cursor-based search, payment/refund
aggregation, approval tracking, reconciliation mismatch detection,
and audit trail queries."
```

### Task 8: Create JPAQueryFactory configuration

QueryDSL requires a `JPAQueryFactory` bean wired with `EntityManager`.

**Files:**
- Create: `settlement/adapter/out/persistence/querydsl/QueryDslConfig.java`

- [ ] **Step 1: Create config**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryDslConfig {

    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/QueryDslConfig.java
git commit -m "feat: add JPAQueryFactory configuration for QueryDSL"
```

---

## Chunk 3: Application Service, Controller, and Wiring

### Task 9: Create application service

**Files:**
- Create: `settlement/application/service/SettlementQueryService.java`

- [ ] **Step 1: Create SettlementQueryService**

```java
package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.settlement.application.port.out.QuerySettlementPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 조회 Application Service
 *
 * readOnly=true: 읽기 전용 트랜잭션
 * - Hibernate flush mode를 MANUAL로 설정하여 dirty checking 비용 제거
 * - PostgreSQL replica로 라우팅 가능 (Read/Write 분리 시)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SettlementQueryService {

    private final QuerySettlementPort querySettlementPort;

    public List<SettlementSummaryDto> getDailySummary(LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.findDailySummary(startDate, endDate);
    }

    public List<SettlementSummaryDto> getMonthlySummary(LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.findMonthlySummary(startDate, endDate);
    }

    public SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(
            SettlementSearchCondition condition) {
        return querySettlementPort.searchSettlements(condition);
    }

    public PaymentRefundAggregationDto getPaymentRefundAggregation(
            LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.getPaymentRefundAggregation(startDate, endDate);
    }

    public SettlementCursorPageResponse<ApprovalStatusDto> getApprovalStatus(
            String status, int size, Long cursorId) {
        return querySettlementPort.findByApprovalStatus(status, size, cursorId);
    }

    public List<SettlementReconciliationDto> getReconciliationMismatches(
            LocalDate startDate, LocalDate endDate) {
        return querySettlementPort.findReconciliationMismatches(startDate, endDate);
    }

    public List<SettlementDetailDto> getAuditTrail(Long paymentId) {
        return querySettlementPort.findAuditTrailByPaymentId(paymentId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/application/service/SettlementQueryService.java
git commit -m "feat: add SettlementQueryService application service"
```

### Task 10: Create QuerySettlementPort adapter implementation

Wire the QueryDSL repository to the hexagonal port.

**Files:**
- Create: `settlement/adapter/out/persistence/querydsl/SettlementQueryAdapter.java`

- [ ] **Step 1: Create adapter**

```java
package github.lms.lemuel.settlement.adapter.out.persistence.querydsl;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.settlement.application.port.out.QuerySettlementPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementQueryAdapter implements QuerySettlementPort {

    private final SettlementQueryRepository queryRepository;

    @Override
    public List<SettlementSummaryDto> findDailySummary(LocalDate startDate, LocalDate endDate) {
        return queryRepository.findDailySummary(startDate, endDate);
    }

    @Override
    public List<SettlementSummaryDto> findMonthlySummary(LocalDate startDate, LocalDate endDate) {
        return queryRepository.findMonthlySummary(startDate, endDate);
    }

    @Override
    public SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition) {
        return queryRepository.searchSettlements(condition);
    }

    @Override
    public PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate) {
        return queryRepository.getPaymentRefundAggregation(startDate, endDate);
    }

    @Override
    public SettlementCursorPageResponse<ApprovalStatusDto> findByApprovalStatus(
            String status, int size, Long cursorId) {
        return queryRepository.findByApprovalStatus(status, size, cursorId);
    }

    @Override
    public List<SettlementReconciliationDto> findReconciliationMismatches(
            LocalDate startDate, LocalDate endDate) {
        return queryRepository.findReconciliationMismatches(startDate, endDate);
    }

    @Override
    public List<SettlementDetailDto> findAuditTrailByPaymentId(Long paymentId) {
        return queryRepository.findAuditTrailByPaymentId(paymentId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/out/persistence/querydsl/SettlementQueryAdapter.java
git commit -m "feat: add SettlementQueryAdapter wiring port to QueryDSL repository"
```

### Task 11: Create REST controller

**Files:**
- Create: `settlement/adapter/in/web/SettlementQueryController.java`

- [ ] **Step 1: Create controller**

```java
package github.lms.lemuel.settlement.adapter.in.web;

import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.settlement.application.service.SettlementQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/settlements/query")
@RequiredArgsConstructor
public class SettlementQueryController {

    private final SettlementQueryService queryService;

    /**
     * 일별 정산 요약
     * GET /api/settlements/query/summary/daily?startDate=2026-01-01&endDate=2026-01-31
     */
    @GetMapping("/summary/daily")
    public ResponseEntity<List<SettlementSummaryDto>> getDailySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getDailySummary(startDate, endDate));
    }

    /**
     * 월별 정산 요약
     * GET /api/settlements/query/summary/monthly?startDate=2026-01-01&endDate=2026-12-31
     */
    @GetMapping("/summary/monthly")
    public ResponseEntity<List<SettlementSummaryDto>> getMonthlySummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getMonthlySummary(startDate, endDate));
    }

    /**
     * 정산 상세 검색 (Cursor 기반)
     * GET /api/settlements/query/search?status=DONE&startDate=2026-01-01&size=20&cursorId=100&cursorDate=2026-01-15
     */
    @GetMapping("/search")
    public ResponseEntity<SettlementCursorPageResponse<SettlementDetailDto>> searchSettlements(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String ordererName,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) Boolean isRefunded,
            @RequestParam(required = false) Long cursorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cursorDate,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "settlementDate") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {

        SettlementSearchCondition condition = SettlementSearchCondition.builder()
                .status(status)
                .startDate(startDate)
                .endDate(endDate)
                .userId(userId)
                .ordererName(ordererName)
                .productName(productName)
                .isRefunded(isRefunded)
                .cursorId(cursorId)
                .cursorDate(cursorDate)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();

        return ResponseEntity.ok(queryService.searchSettlements(condition));
    }

    /**
     * 결제/환불 집계
     * GET /api/settlements/query/aggregation?startDate=2026-01-01&endDate=2026-01-31
     */
    @GetMapping("/aggregation")
    public ResponseEntity<PaymentRefundAggregationDto> getPaymentRefundAggregation(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getPaymentRefundAggregation(startDate, endDate));
    }

    /**
     * 승인 상태 추적
     * GET /api/settlements/query/approvals?status=WAITING_APPROVAL&size=20&cursorId=100
     */
    @GetMapping("/approvals")
    public ResponseEntity<SettlementCursorPageResponse<ApprovalStatusDto>> getApprovalStatus(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long cursorId) {
        return ResponseEntity.ok(queryService.getApprovalStatus(status, size, cursorId));
    }

    /**
     * 대사 불일치 탐지
     * GET /api/settlements/query/reconciliation?startDate=2026-01-01&endDate=2026-01-31
     */
    @GetMapping("/reconciliation")
    public ResponseEntity<List<SettlementReconciliationDto>> getReconciliationMismatches(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(queryService.getReconciliationMismatches(startDate, endDate));
    }

    /**
     * 감사 추적 (결제 ID 기준)
     * GET /api/settlements/query/audit/payment/123
     */
    @GetMapping("/audit/payment/{paymentId}")
    public ResponseEntity<List<SettlementDetailDto>> getAuditTrail(@PathVariable Long paymentId) {
        return ResponseEntity.ok(queryService.getAuditTrail(paymentId));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/github/lms/lemuel/settlement/adapter/in/web/SettlementQueryController.java
git commit -m "feat: add SettlementQueryController REST endpoints"
```

---

## Chunk 4: Design Review and Risk Analysis

This chunk is documentation only — no code changes.

### Design Review Summary

#### 1. Performance Issues

| Issue | Impact | Mitigation |
|-------|--------|------------|
| **Monthly GROUP BY with date_trunc()** | PostgreSQL cannot use B-Tree index for function-wrapped columns | Covering index `idx_settlements_date_status_amounts` with INCLUDE ensures data is available without table heap access. For extreme volumes (>100M rows), consider a materialized view `settlement_monthly_summary` refreshed by batch. |
| **Multi-table JOIN in searchSettlements()** | 4-table JOIN (settlement → payment, order → user + product) at scale | Each JOIN uses indexed FK columns. LEFT JOIN on product is necessary (orders may lack product_id). The covering indexes minimize random I/O. |
| **Aggregation queries scan full date range** | SUM/COUNT over large date ranges | `idx_settlements_date_status_amounts` INCLUDE clause enables Index-Only Scan for aggregation. For dashboard use, cache results with Caffeine (already configured). |
| **LIKE queries for ordererName/productName** | `containsIgnoreCase` generates `LOWER(email) LIKE '%keyword%'` — cannot use B-Tree index | Acceptable for admin search. For high-frequency use, add `pg_trgm` extension + GIN index, or route to Elasticsearch (already configured). |

#### 2. Transaction Boundary Risks

| Risk | WHY it matters | Mitigation |
|------|---------------|------------|
| **Read queries holding long transactions** | `@Transactional(readOnly=true)` still acquires a snapshot. Long-running aggregations can block VACUUM. | Acceptable for short queries. For batch reconciliation over millions of rows, consider running outside transaction or with `statement_timeout`. |
| **No write-side isolation** | Query module is read-only by design | `@Transactional(readOnly=true)` at service level prevents accidental writes. Hibernate flush mode = MANUAL. |

#### 3. Data Consistency Risks (Settlement Accuracy)

| Risk | WHY it matters | Mitigation |
|------|---------------|------------|
| **Payment ↔ Settlement amount drift** | If `CreateDailySettlementsService` has a bug, `settlements.payment_amount` may differ from `payments.amount` | `findReconciliationMismatches()` query detects this. Run daily as batch reconciliation. |
| **Refund not reflected in settlement** | `adjustForRefund()` could fail silently, leaving `settlements.refunded_amount` stale | Reconciliation query compares `payments.refunded_amount` vs `settlements.refunded_amount`. |
| **Duplicate settlement per payment** | Unique index `idx_settlements_payment_id_unique` prevents duplicates at DB level | Already enforced by V3 migration. Query will never return duplicates. |
| **Missing settlement for captured payment** | If batch fails mid-run, some captured payments may lack settlements | Reconciliation audit: `SELECT p.id FROM payments p LEFT JOIN settlements s ON p.id = s.payment_id WHERE s.id IS NULL AND p.status = 'CAPTURED'` — add this as an additional audit query. |

#### 4. Index Strategy

| Index | Purpose | Type |
|-------|---------|------|
| `idx_settlements_date_id_desc` | Cursor pagination `ORDER BY date DESC, id DESC` | B-Tree (DESC, DESC) |
| `idx_settlements_date_status_amounts` | Daily/monthly aggregation with Index-Only Scan | B-Tree + INCLUDE |
| `idx_settlements_payment_id_amounts` | Reconciliation JOIN with covering data | B-Tree + INCLUDE |
| `idx_settlements_approval_status` | Partial index for approval-related status only | Partial B-Tree |
| `idx_payments_captured_status_amount` | Batch settlement creation query | B-Tree + INCLUDE |
| Existing `idx_settlements_date_status` | Kept for backward compatibility with batch queries | B-Tree (composite) |

#### 5. QueryDSL Anti-Patterns Avoided

| Anti-Pattern | How we avoid it |
|-------------|----------------|
| **Returning entities from read queries** | All queries use `Projections.constructor()` → DTO only |
| **N+1 via lazy loading** | No entity relationships → no lazy loading. Single query per request. |
| **fetchJoin for read model** | Not used. Read model uses flat projections, not entity graphs. |
| **Dynamic WHERE via string concatenation** | `BooleanBuilder` with type-safe Q-classes |
| **OFFSET pagination** | Cursor-based with `(date, id)` composite cursor |
| **SELECT * equivalent** | Explicit field selection in Projections.constructor() |

#### 6. High Concurrency Behavior

| Scenario | Behavior |
|----------|----------|
| **Multiple readers** | PostgreSQL MVCC ensures readers never block readers. `readOnly=true` uses snapshot isolation. |
| **Reader + Writer** | Writers (batch settlement creation, refund adjustment) don't block read queries due to MVCC. |
| **Aggregation during batch** | May see partially committed batch data within same transaction snapshot. Acceptable for dashboard; for financial reports, query only completed batches (status = DONE/CONFIRMED). |
| **Connection pool exhaustion** | Read queries are short-lived. Use HikariCP `maximum-pool-size` tuning (default 10). For read/write separation, configure separate DataSource for read replica. |

#### 7. Financial Correctness Validation

**Idempotency Key Strategy:**
- Already enforced at refund level: `idx_refunds_payment_idempotency` (payment_id + idempotency_key)
- Settlement creation is idempotent via `idx_settlements_payment_id_unique` — one settlement per payment

**Deduplication Logic:**
- DB-level: UNIQUE constraints prevent duplicate settlements and duplicate refunds
- Application-level: `findByPaymentId()` check before creation in `CreateDailySettlementsService`

**Reconciliation Query Patterns:**
1. **Amount mismatch**: `findReconciliationMismatches()` — compares payment vs settlement amounts
2. **Missing settlements**: Query payments without corresponding settlements
3. **Orphan settlements**: Query settlements whose payment no longer exists or is canceled
4. **Daily balance check**: `getDailySummary()` totals should match external PG settlement reports

**Audit Queries:**
- `findAuditTrailByPaymentId()` — full lifecycle of a single payment's settlement
- Combined with `settlement_adjustments` table for complete refund adjustment history

---

### Improvement Suggestions

1. **Read/Write Separation**: Configure `@Transactional(readOnly=true)` routing to PostgreSQL read replica via `AbstractRoutingDataSource`. The `SettlementQueryService` already uses `readOnly=true`.

2. **Caching**: Add Caffeine cache for `getDailySummary()` and `getMonthlySummary()` with TTL matching batch frequency (e.g., 10 minutes). Historical months never change — cache indefinitely.

3. **Materialized View**: For monthly summary with >50M rows, create a PostgreSQL materialized view refreshed by the settlement batch job.

4. **Missing Settlement Detection**: Add a reconciliation query to detect captured payments without settlements:
```java
List<Long> findPaymentsWithoutSettlement(LocalDate capturedDate);
```

5. **Batch Reconciliation Job**: Schedule `findReconciliationMismatches()` as a daily batch job that alerts on any discrepancies via existing metrics/monitoring infrastructure.
