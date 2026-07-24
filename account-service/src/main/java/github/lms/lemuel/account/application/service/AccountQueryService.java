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
        // ADR 0026 Option A: 정산 확정(settlement.confirmed)은 GL 무전표라 SETTLEMENT_CONFIRMED 는
        // 신규 적재되지 않는다(SettlementConfirmedConsumer.handle no-op). 확정을 SETTLEMENT_CONFIRMED 합계로
        // 읽으면 항상 ~0 이 되어 지급 완료 정산까지 영구 미지급으로 오표기된다.
        //
        // scheduled(전체 정산 인식액) = 즉시분(SETTLEMENT_CREATED, I) + 유보분(SETTLEMENT_HOLDBACK_RECOGNIZED, H).
        //   유보를 포함해야 유보 해제→지급(PAYOUT_COMPLETED 에 합산됨) 시 pending 이 음수로 새지 않는다.
        // confirmed(실지급) = 실제 현금 유출(PAYOUT_COMPLETED, Option A 지급 시점) + 레거시 cut-over 확정
        //   (SETTLEMENT_CONFIRMED — 옛 모델 적재분만 남으며, 한 정산은 둘 중 한쪽에만 기여해 이중계상 없음).
        BigDecimal scheduled = loadAccountEntryPort.sumAmountByRefType("SETTLEMENT_CREATED")
                .add(loadAccountEntryPort.sumAmountByRefType("SETTLEMENT_HOLDBACK_RECOGNIZED"));
        BigDecimal confirmed = loadAccountEntryPort.sumAmountByRefType("PAYOUT_COMPLETED")
                .add(loadAccountEntryPort.sumAmountByRefType("SETTLEMENT_CONFIRMED"));
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
