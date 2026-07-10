package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.AccountQueryUseCase.EntryPage;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase.InvestmentAggregate;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase.LoanAggregate;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase.SettlementAggregate;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import github.lms.lemuel.account.domain.TrialBalance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountQueryServiceTest {

    @Mock LoadAccountEntryPort loadAccountEntryPort;
    @InjectMocks AccountQueryService service;

    @Test
    void accountSummary_는_owner전표를_요약으로_접는다() {
        when(loadAccountEntryPort.findByOwner(OwnerType.SELLER, "55")).thenReturn(List.of(
                AccountEntry.loanDisbursed("55", "L1", new BigDecimal("800000")),
                AccountEntry.loanRepaid("55", "S1", new BigDecimal("300000"))));

        AccountSummary summary = service.accountSummary(OwnerType.SELLER, "55");

        assertThat(summary.ownerId()).isEqualTo("55");
        assertThat(summary.entryCount()).isEqualTo(2);
        assertThat(summary.balances()).anySatisfy(b -> {
            assertThat(b.account()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
            assertThat(b.balance()).isEqualByComparingTo("500000");
        });
    }

    @Test
    void entries_는_페이지_content와_total을_묶는다() {
        List<AccountEntry> content = List.of(
                AccountEntry.investmentExecuted("55", "O1", new BigDecimal("250000")));
        when(loadAccountEntryPort.findByOwnerPaged(OwnerType.SELLER, "55", 0, 20)).thenReturn(content);
        when(loadAccountEntryPort.countByOwner(OwnerType.SELLER, "55")).thenReturn(1L);

        EntryPage page = service.entries(OwnerType.SELLER, "55", 0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1L);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(20);
    }

    @Test
    void loanAggregates_는_선지급빼기상환으로_outstanding을_낸다() {
        when(loadAccountEntryPort.sumAmountByRefType("LOAN_DISBURSED")).thenReturn(new BigDecimal("1000000"));
        when(loadAccountEntryPort.sumAmountByRefType("LOAN_REPAID")).thenReturn(new BigDecimal("300000"));
        when(loadAccountEntryPort.sumAmountByRefType("CORP_LOAN_DISBURSED")).thenReturn(new BigDecimal("5000000"));
        when(loadAccountEntryPort.countByRefType("LOAN_DISBURSED")).thenReturn(3L);
        when(loadAccountEntryPort.countByRefType("LOAN_REPAID")).thenReturn(2L);
        when(loadAccountEntryPort.countByRefType("CORP_LOAN_DISBURSED")).thenReturn(1L);

        LoanAggregate agg = service.loanAggregates();

        assertThat(agg.disbursedTotal()).isEqualByComparingTo("1000000");
        assertThat(agg.repaidTotal()).isEqualByComparingTo("300000");
        assertThat(agg.outstanding()).isEqualByComparingTo("700000");
        assertThat(agg.corporateDisbursedTotal()).isEqualByComparingTo("5000000");
        assertThat(agg.corporateOutstanding()).isEqualByComparingTo("5000000");
        assertThat(agg.entryCount()).isEqualTo(6L);
    }

    @Test
    void investmentAggregates_는_합계와_건수() {
        when(loadAccountEntryPort.sumAmountByRefType("INVESTMENT_EXECUTED")).thenReturn(new BigDecimal("250000"));
        when(loadAccountEntryPort.countByRefType("INVESTMENT_EXECUTED")).thenReturn(4L);

        InvestmentAggregate agg = service.investmentAggregates();

        assertThat(agg.investedTotal()).isEqualByComparingTo("250000");
        assertThat(agg.orderCount()).isEqualTo(4L);
    }

    @Test
    void settlementAggregates_는_예정빼기확정으로_pending을_낸다() {
        when(loadAccountEntryPort.sumAmountByRefType("SETTLEMENT_CREATED")).thenReturn(new BigDecimal("100000"));
        when(loadAccountEntryPort.sumAmountByRefType("SETTLEMENT_CONFIRMED")).thenReturn(new BigDecimal("40000"));

        SettlementAggregate agg = service.settlementAggregates();

        assertThat(agg.scheduledTotal()).isEqualByComparingTo("100000");
        assertThat(agg.confirmedTotal()).isEqualByComparingTo("40000");
        assertThat(agg.pendingScheduled()).isEqualByComparingTo("60000");
    }

    @Test
    void trialBalance_는_전체전표로_시산표를_만든다() {
        when(loadAccountEntryPort.findAll()).thenReturn(List.of(
                AccountEntry.loanDisbursed("1", "L1", new BigDecimal("800000")),
                AccountEntry.investmentExecuted("1", "O1", new BigDecimal("250000"))));

        TrialBalance tb = service.trialBalance();

        assertThat(tb.balanced()).isTrue();
        assertThat(tb.totalDebit()).isEqualByComparingTo("1050000");
        assertThat(tb.totalCredit()).isEqualByComparingTo("1050000");
    }
}
