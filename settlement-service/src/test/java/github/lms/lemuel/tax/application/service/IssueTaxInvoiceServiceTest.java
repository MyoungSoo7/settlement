package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.dto.TaxSettlementView;
import github.lms.lemuel.tax.application.port.out.LoadSellerTaxProfilePort;
import github.lms.lemuel.tax.application.port.out.LoadSettlementForTaxPort;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.application.port.out.SaveTaxInvoicePort;
import github.lms.lemuel.tax.domain.SellerTaxProfile;
import github.lms.lemuel.tax.domain.TaxCalculation;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.TaxType;
import github.lms.lemuel.tax.domain.exception.TaxInvariantViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueTaxInvoiceServiceTest {

    private LoadSettlementForTaxPort settlementPort;
    private LoadSellerTaxProfilePort profilePort;
    private LoadTaxInvoicePort loadInvoicePort;
    private SaveTaxInvoicePort saveInvoicePort;
    private IssueTaxInvoiceService service;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    @BeforeEach
    void setUp() {
        settlementPort = mock(LoadSettlementForTaxPort.class);
        profilePort = mock(LoadSellerTaxProfilePort.class);
        loadInvoicePort = mock(LoadTaxInvoicePort.class);
        saveInvoicePort = mock(SaveTaxInvoicePort.class);
        service = new IssueTaxInvoiceService(
                new TaxContextResolver(settlementPort, profilePort), loadInvoicePort, saveInvoicePort);
        when(saveInvoicePort.save(any(TaxInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void okContext(TaxType type) {
        when(settlementPort.findById(100L)).thenReturn(Optional.of(new TaxSettlementView(
                100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, "DONE",
                new BigDecimal("96500.00"))));
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.of(
                type == TaxType.BUSINESS
                        ? SellerTaxProfile.register(7L, TaxType.BUSINESS, "1234567890")
                        : SellerTaxProfile.register(7L, TaxType.INDIVIDUAL, null)));
    }

    @Test
    void 신규_발행_공급가액과_세액_포함과세() {
        okContext(TaxType.INDIVIDUAL);
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.empty());

        Optional<TaxInvoice> result = service.issueForSettlement(100L, 7L);

        assertThat(result).isPresent();
        assertThat(result.get().getSupplyAmount()).isEqualByComparingTo("3182"); // 3500 - 318(vat)
        assertThat(result.get().getTaxAmount()).isEqualByComparingTo("318");
        assertThat(result.get().getTotalAmount()).isEqualByComparingTo("3500.00"); // = commission
        verify(saveInvoicePort).save(any(TaxInvoice.class));
    }

    @Test
    void 이미_발행됐으면_기존_반환_저장안함() {
        TaxCalculation calc = TaxCalculation.of(new BigDecimal("3500.00"), new BigDecimal("96500.00"), TaxType.INDIVIDUAL);
        TaxInvoice existing = TaxInvoice.issue(100L, 7L, calc, DATE);
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.of(existing));

        Optional<TaxInvoice> result = service.issueForSettlement(100L, 7L);

        assertThat(result).contains(existing);
        verify(saveInvoicePort, never()).save(any());
    }

    @Test
    void 미등록_셀러면_빈결과() {
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.empty());
        when(settlementPort.findById(100L)).thenReturn(Optional.of(new TaxSettlementView(
                100L, new BigDecimal("3500.00"), new BigDecimal("96500.00"), DATE, "DONE",
                new BigDecimal("96500.00"))));
        when(profilePort.findBySellerId(7L)).thenReturn(Optional.empty());

        assertThat(service.issueForSettlement(100L, 7L)).isEmpty();
    }

    @Test
    void 인자_null_예외() {
        assertThatThrownBy(() -> service.issueForSettlement(null, 7L))
                .isInstanceOf(TaxInvariantViolationException.class);
    }

    @Test
    void 동시_발행_경합은_재조회로_수렴() {
        okContext(TaxType.INDIVIDUAL);
        TaxCalculation calc = TaxCalculation.of(new BigDecimal("3500.00"), new BigDecimal("96500.00"), TaxType.INDIVIDUAL);
        TaxInvoice winner = TaxInvoice.issue(100L, 7L, calc, DATE);
        when(loadInvoicePort.findBySettlementId(100L))
                .thenReturn(Optional.empty())        // 최초 멱등 체크
                .thenReturn(Optional.of(winner));    // 경합 후 재조회
        when(saveInvoicePort.save(any(TaxInvoice.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        Optional<TaxInvoice> result = service.issueForSettlement(100L, 7L);

        assertThat(result).contains(winner);
    }
}
