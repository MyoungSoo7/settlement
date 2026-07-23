package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.account.domain.TrialBalance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 계정계 조회 유스케이스 — 합계/건수는 포트의 DB 집계로, 잔액/시산표/요약은 도메인 계산으로 산출한다.
 */
@Service
@Transactional(readOnly = true)
public class AccountQueryService implements AccountQueryUseCase {

    private final LoadAccountEntryPort loadAccountEntryPort;

    public AccountQueryService(LoadAccountEntryPort loadAccountEntryPort) {
        this.loadAccountEntryPort = loadAccountEntryPort;
    }

    @Override
    public AccountSummary accountSummary(OwnerType ownerType, String ownerId) {
        List<AccountEntry> entries = loadAccountEntryPort.findByOwner(ownerType, ownerId);
        return AccountSummary.of(ownerType, ownerId, entries);
    }

    @Override
    public EntryPage entries(OwnerType ownerType, String ownerId, int page, int size) {
        List<AccountEntry> content = loadAccountEntryPort.findByOwnerPaged(ownerType, ownerId, page, size);
        long total = loadAccountEntryPort.countByOwner(ownerType, ownerId);
        return new EntryPage(content, total, page, size);
    }

    @Override
    public LoanAggregate loanAggregates() {
        BigDecimal disbursed = loadAccountEntryPort.sumAmountByRefType("LOAN_DISBURSED");
        BigDecimal repaid = loadAccountEntryPort.sumAmountByRefType("LOAN_REPAID");
        BigDecimal corporateDisbursed = loadAccountEntryPort.sumAmountByRefType("CORP_LOAN_DISBURSED");
        long entryCount = loadAccountEntryPort.countByRefType("LOAN_DISBURSED")
                + loadAccountEntryPort.countByRefType("LOAN_REPAID")
                + loadAccountEntryPort.countByRefType("CORP_LOAN_DISBURSED");
        return new LoanAggregate(
                disbursed,
                repaid,
                disbursed.subtract(repaid),
                corporateDisbursed,
                corporateDisbursed,
                entryCount);
    }

    @Override
    public InvestmentAggregate investmentAggregates() {
        BigDecimal invested = loadAccountEntryPort.sumAmountByRefType("INVESTMENT_EXECUTED");
        long orderCount = loadAccountEntryPort.countByRefType("INVESTMENT_EXECUTED");
        return new InvestmentAggregate(invested, orderCount);
    }

    @Override
    public SettlementAggregate settlementAggregates() {
        BigDecimal scheduled = loadAccountEntryPort.sumAmountByRefType("SETTLEMENT_CREATED");
        BigDecimal confirmed = loadAccountEntryPort.sumAmountByRefType("SETTLEMENT_CONFIRMED");
        return new SettlementAggregate(scheduled, confirmed, scheduled.subtract(confirmed));
    }

    @Override
    public TrialBalance trialBalance() {
        return TrialBalance.of(loadAccountEntryPort.findAll());
    }

    @Override
    public TrialBalance trialBalance(LocalDateTime fromInclusive, LocalDateTime toExclusive) {
        return TrialBalance.of(loadAccountEntryPort.findByOccurredAtBetween(fromInclusive, toExclusive));
    }

    @Override
    public ControlRecon controlRecon() {
        TrialBalance tb = TrialBalance.of(loadAccountEntryPort.findAll());
        return new ControlRecon(
                tb.normalBalance(GlAccount.SELLER_PAYABLE),
                tb.normalBalance(GlAccount.HOLDBACK_PAYABLE),
                tb.normalBalance(GlAccount.SELLER_RECOVERY_RECEIVABLE));
    }
}
