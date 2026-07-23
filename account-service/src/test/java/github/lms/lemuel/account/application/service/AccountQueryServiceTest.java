package github.lms.lemuel.account.application.service;

import github.lms.lemuel.account.application.port.in.OwnerAccountQuery.EntryPage;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.InvestmentAggregate;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.LoanAggregate;
import github.lms.lemuel.account.application.port.in.AccountAggregateQuery.SettlementAggregate;
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

    @Test
    void trialBalance_기간_은_occurredAt_반개구간_전표로_시산표를_만든다() {
        java.time.LocalDateTime from = java.time.LocalDateTime.of(2026, 7, 1, 0, 0);
        java.time.LocalDateTime to = java.time.LocalDateTime.of(2026, 8, 1, 0, 0);
        when(loadAccountEntryPort.findByOccurredAtBetween(from, to)).thenReturn(List.of(
                AccountEntry.settlementCreatedImmediate("777", "S1", new BigDecimal("43425")),
                AccountEntry.payoutCompleted("777", "P1", new BigDecimal("43425"))));

        TrialBalance tb = service.trialBalance(from, to);

        // created(DR CASH/CR PAYABLE) + payout(DR PAYABLE/CR CASH) → 폐루프, 정상방향 준수
        assertThat(tb.balanced()).isTrue();
        assertThat(tb.normalBalanceRespected()).isTrue();
        assertThat(tb.totalDebit()).isEqualByComparingTo("86850");
    }

    @Test
    void controlRecon_완전정산이면_세_통제계정_순잔액_0_balanced_참() {
        // 즉시 인식·지급, 유보 인식·소진, 회수 발생·상계가 모두 상쇄되는 전표 집합
        when(loadAccountEntryPort.findAll()).thenReturn(List.of(
                AccountEntry.settlementCreatedImmediate("777", "A", new BigDecimal("700")),
                AccountEntry.payoutCompleted("777", "payA", new BigDecimal("700")),
                AccountEntry.settlementHoldbackRecognized("777", "A", new BigDecimal("300")),
                AccountEntry.holdbackConsumed("777", "adjA", new BigDecimal("300")),
                AccountEntry.settlementCreatedImmediate("777", "C", new BigDecimal("200")), // 상계 재원 공급
                AccountEntry.recoveryOpened("777", "r1", new BigDecimal("200")),
                AccountEntry.recoveryOffset("777", "al1", new BigDecimal("200"))));

        var recon = service.controlRecon();

        assertThat(recon.sellerPayable()).isEqualByComparingTo("0");
        assertThat(recon.holdbackPayable()).isEqualByComparingTo("0");
        assertThat(recon.recoveryReceivable()).isEqualByComparingTo("0");
        assertThat(recon.balanced()).isTrue();
    }

    @Test
    void controlRecon_미해결_유보와_회수는_정상방향_순잔액으로_노출된다() {
        when(loadAccountEntryPort.findAll()).thenReturn(List.of(
                AccountEntry.settlementHoldbackRecognized("777", "A", new BigDecimal("300")), // CR HOLDBACK_PAYABLE 300
                AccountEntry.recoveryOpened("777", "r1", new BigDecimal("200"))));            // DR RECEIVABLE 200

        var recon = service.controlRecon();

        assertThat(recon.holdbackPayable()).isEqualByComparingTo("300");   // 미해제 홀드백
        assertThat(recon.recoveryReceivable()).isEqualByComparingTo("200"); // OPEN 회수채권
        assertThat(recon.sellerPayable()).isEqualByComparingTo("0");
        assertThat(recon.balanced()).isFalse();
    }
}
