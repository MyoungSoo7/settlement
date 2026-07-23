package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.ledger.application.port.out.LoadLedgerEntryPort;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxCalculation;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.TaxJournal;
import github.lms.lemuel.tax.domain.TaxReconciliation;
import github.lms.lemuel.tax.domain.TaxType;
import github.lms.lemuel.tax.domain.exception.SellerTaxProfileNotRegisteredException;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaxReconciliationServiceTest {

    private LoadSettlementForTaxPort settlementPort;
    private LoadSellerTaxProfilePort profilePort;
    private LoadTaxInvoicePort loadInvoicePort;
    private LoadLedgerEntryPort loadLedgerPort;
    private LoadPayoutPort loadPayoutPort;
    private TaxReconciliationService service;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    @BeforeEach
    void setUp() {
        settlementPort = mock(LoadSettlementForTaxPort.class);
        profilePort = mock(LoadSellerTaxProfilePort.class);
        loadInvoicePort = mock(LoadTaxInvoicePort.class);
        loadLedgerPort = mock(LoadLedgerEntryPort.class);
        loadPayoutPort = mock(LoadPayoutPort.class);
        service = new TaxReconciliationService(
                new TaxContextResolver(settlementPort, profilePort), loadInvoicePort, loadLedgerPort, loadPayoutPort);
    }

    private void okSettlement() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(new TaxSettlementView(
                100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, "DONE",
                new BigDecimal("96500.00"))));
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.of(
                SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));
    }

    private Payout payoutWithAmount(BigDecimal amount) {
        Payout p = mock(Payout.class);
        when(p.getAmount()).thenReturn(amount);
        return p;
    }

    @Test
    void 정합이면_matched_true() {
        okSettlement();
        TaxCalculation calc = TaxCalculation.of(new BigDecimal("3500.00"), new BigDecimal("96500.00"), TaxType.INDIVIDUAL);
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.of(TaxInvoice.issue(100L, 7L, calc, DATE)));
        when(loadLedgerPort.findByReference(100L, ReferenceType.SETTLEMENT_TAX))
                .thenReturn(TaxJournal.postingsFor(100L, calc, DATE));
        // 실제 payout: 96500 - withholding(3184) = 93316 → 원천징수 교차검증도 정합.
        // (payoutWithAmount 를 when(...).thenReturn(...) 인자 안에서 직접 호출하면 내부의 중첩 when() 이
        // 바깥 스터빙의 "미완료 상태"를 오염시켜 UnfinishedStubbingException 이 난다 — 반드시 먼저 변수로 분리.)
        Payout payout = payoutWithAmount(new BigDecimal("93316"));
        when(loadPayoutPort.findBySettlementIdAndType(100L, PayoutType.IMMEDIATE))
                .thenReturn(Optional.of(payout));

        TaxReconciliation recon = service.reconcile(100L, 7L);

        assertThat(recon.matched()).isTrue();
        assertThat(recon.actualWithholdingDeducted()).isEqualByComparingTo("3184");
    }

    @Test
    void payout_미존재면_원천징수_교차검증_없이도_나머지_정합이면_matched() {
        okSettlement();
        TaxCalculation calc = TaxCalculation.of(new BigDecimal("3500.00"), new BigDecimal("96500.00"), TaxType.INDIVIDUAL);
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.of(TaxInvoice.issue(100L, 7L, calc, DATE)));
        when(loadLedgerPort.findByReference(100L, ReferenceType.SETTLEMENT_TAX))
                .thenReturn(TaxJournal.postingsFor(100L, calc, DATE));
        when(loadPayoutPort.findBySettlementIdAndType(100L, PayoutType.IMMEDIATE)).thenReturn(Optional.empty());

        TaxReconciliation recon = service.reconcile(100L, 7L);

        assertThat(recon.matched()).isTrue();
        assertThat(recon.actualWithholdingDeducted()).isNull();
    }

    @Test
    void 미등록_셀러면_예외() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(new TaxSettlementView(
                100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, "DONE",
                new BigDecimal("96500.00"))));
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcile(100L, 7L))
                .isInstanceOf(SellerTaxProfileNotRegisteredException.class);
    }

    @Test
    void 미확정_정산이면_예외() {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(new TaxSettlementView(
                100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, "PROCESSING",
                new BigDecimal("96500.00"))));
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.of(
                SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));
        assertThatThrownBy(() -> service.reconcile(100L, 7L))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 정산_없으면_예외() {
        when(settlementPort.findById(100L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcile(100L, 7L))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 세금계산서_미발행이면_예외() {
        okSettlement();
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reconcile(100L, 7L))
                .isInstanceOf(TaxInvariantViolationException.class)
                .hasMessageContaining("세금계산서");
    }
}
