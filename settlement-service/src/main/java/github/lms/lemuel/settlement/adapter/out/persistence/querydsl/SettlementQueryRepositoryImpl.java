package github.lms.lemuel.settlement.adapter.out.persistence.querydsl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import github.lms.lemuel.settlement.adapter.out.persistence.QSettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.querydsl.dto.*;
import github.lms.lemuel.settlement.adapter.out.readmodel.QSettlementOrderReadModel;
import github.lms.lemuel.settlement.adapter.out.readmodel.QSettlementPaymentReadModel;
import github.lms.lemuel.settlement.adapter.out.readmodel.QSettlementProductReadModel;
import github.lms.lemuel.settlement.adapter.out.readmodel.QSettlementUserReadModel;
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
    private static final QSettlementPaymentReadModel payment = QSettlementPaymentReadModel.settlementPaymentReadModel;
    private static final QSettlementOrderReadModel order = QSettlementOrderReadModel.settlementOrderReadModel;
    private static final QSettlementUserReadModel user = QSettlementUserReadModel.settlementUserReadModel;
    private static final QSettlementProductReadModel product = QSettlementProductReadModel.settlementProductReadModel;

    /**
     * 일별 정산 요약
     * INDEX: idx_settlements_date_status_amounts (covering index → Index-Only Scan)
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
     * PostgreSQL date_trunc('month', settlement_date)로 월별 그룹핑
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
     * Cursor(settlement_date, id) 복합 커서로 O(log n) 탐색
     * INDEX: idx_settlements_date_id_desc
     * N+1 방지: 단일 쿼리로 settlement/payment/order/user/product 모두 프로젝션
     */
    @Override
    public SettlementCursorPageResponse<SettlementDetailDto> searchSettlements(SettlementSearchCondition condition) {
        int fetchSize = Math.min(condition.getSize() > 0 ? condition.getSize() : 20, 100);

        BooleanBuilder where = buildSearchWhere(condition);

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
                .limit(fetchSize + 1)
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
     * settlements 테이블만 사용 (JOIN 불필요, 성능 최적)
     */
    @Override
    public PaymentRefundAggregationDto getPaymentRefundAggregation(LocalDate startDate, LocalDate endDate) {
        var result = queryFactory
                .select(
                        settlement.count(),
                        settlement.paymentAmount.sum().coalesce(BigDecimal.ZERO),
                        Expressions.cases()
                                .when(settlement.refundedAmount.gt(BigDecimal.ZERO)).then(1L)
                                .otherwise(0L)
                                .sum(),
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
     * payments.amount ≠ settlements.payment_amount → 정산 생성 시점 버그
     * payments.refunded_amount ≠ settlements.refunded_amount → 환불 반영 누락
     *
     * INDEX: idx_settlements_payment_id_amounts (covering index)
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
                        payment.amount.ne(settlement.paymentAmount)
                                .or(payment.refundedAmount.ne(settlement.refundedAmount))
                )
                .orderBy(settlement.settlementDate.desc(), settlement.id.desc())
                .fetch();
    }

    /**
     * 감사 추적 (Audit Trail)
     * 특정 payment_id에 연결된 정산 건의 전체 이력
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

    private NumberExpression<Long> statusCount(String statusValue) {
        return Expressions.cases()
                .when(settlement.status.eq(statusValue)).then(1L)
                .otherwise(0L)
                .sum();
    }

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
            default -> isAsc
                    ? new OrderSpecifier[]{settlement.settlementDate.asc(), settlement.id.asc()}
                    : new OrderSpecifier[]{settlement.settlementDate.desc(), settlement.id.desc()};
        };
    }
}
