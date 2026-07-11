package github.lms.lemuel.account.application.port.in;

import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.account.domain.TrialBalance;

import java.math.BigDecimal;
import java.util.List;

/**
 * 계정계 조회 인바운드 포트 — owner 잔액·분개 페이지·대출/투자/정산 집계·시산표.
 */
public interface AccountQueryUseCase {

    /** owner 별 계정 잔액 요약. */
    AccountSummary accountSummary(OwnerType ownerType, String ownerId);

    /** owner 별 분개 페이지. */
    EntryPage entries(OwnerType ownerType, String ownerId, int page, int size);

    /** 대출 집계 — "대출을 제대로 집계하는 계정계"의 핵심. */
    LoanAggregate loanAggregates();

    /** 투자 집계. */
    InvestmentAggregate investmentAggregates();

    /** 정산 집계. */
    SettlementAggregate settlementAggregates();

    /** 전사 시산표. */
    TrialBalance trialBalance();

    /** 분개 페이지 결과. */
    record EntryPage(List<AccountEntry> content, long totalElements, int page, int size) { }

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
