package github.lms.lemuel.account.application.port.in;

import java.math.BigDecimal;

/**
 * 전사 집계 조회 역할 — 대출/투자/정산 도메인별 합계 (집계 축).
 *
 * <p>{@link AccountQueryUseCase} 의 응집 축 중 하나. 집계 대시보드만 그리는 소비처는 이 역할만
 * 의존하면 된다(ISP).
 */
public interface AccountAggregateQuery {

    /** 대출 집계 — "대출을 제대로 집계하는 계정계"의 핵심. */
    LoanAggregate loanAggregates();

    /** 투자 집계. */
    InvestmentAggregate investmentAggregates();

    /** 정산 집계. */
    SettlementAggregate settlementAggregates();

    /**
     * 대출 집계 — 셀러 선정산 대출 + 법인 대출.
     * outstanding = disbursed − repaid, corporateOutstanding = 법인 상환 이벤트 부재로 corporateDisbursed 와 동일.
     */
    record LoanAggregate(
            BigDecimal disbursedTotal,
            BigDecimal repaidTotal,
            BigDecimal outstanding,
            BigDecimal corporateDisbursedTotal,
            BigDecimal corporateOutstanding,
            long entryCount) { }

    /** 투자 집계. */
    record InvestmentAggregate(BigDecimal investedTotal, long orderCount) { }

    /** 정산 집계 — pendingScheduled = scheduled − confirmed. */
    record SettlementAggregate(
            BigDecimal scheduledTotal,
            BigDecimal confirmedTotal,
            BigDecimal pendingScheduled) { }
}
