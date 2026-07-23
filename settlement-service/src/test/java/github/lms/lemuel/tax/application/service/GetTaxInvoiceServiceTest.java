package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.application.port.out.RenderTaxInvoicePdfPort;
import github.lms.lemuel.tax.domain.TaxCalculation;
import github.lms.lemuel.tax.domain.TaxInvoice;
import github.lms.lemuel.tax.domain.TaxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetTaxInvoiceServiceTest {

    private LoadTaxInvoicePort loadInvoicePort;
    private RenderTaxInvoicePdfPort renderPdfPort;
    private GetTaxInvoiceService service;

    private static final LocalDate DATE = LocalDate.of(2026, 7, 23);

    @BeforeEach
    void setUp() {
        loadInvoicePort = mock(LoadTaxInvoicePort.class);
        renderPdfPort = mock(RenderTaxInvoicePdfPort.class);
        service = new GetTaxInvoiceService(loadInvoicePort, renderPdfPort);
    }

    private TaxInvoice invoice() {
        TaxCalculation calc = TaxCalculation.of(new BigDecimal("3500.00"), new BigDecimal("96500.00"), TaxType.INDIVIDUAL);
        return TaxInvoice.issue(100L, 7L, calc, DATE);
    }

    @Test
    void 조회_존재() {
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.of(invoice()));
        assertThat(service.bySettlementId(100L)).isPresent();
    }

    @Test
    void 조회_없음() {
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.empty());
        assertThat(service.bySettlementId(100L)).isEmpty();
    }

    @Test
    void PDF_존재하면_렌더링() {
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.of(invoice()));
        when(renderPdfPort.render(any(TaxInvoice.class))).thenReturn(new byte[]{1, 2, 3});
        assertThat(service.renderPdf(100L)).contains(new byte[]{1, 2, 3});
    }

    @Test
    void PDF_없으면_빈결과() {
        when(loadInvoicePort.findBySettlementId(100L)).thenReturn(Optional.empty());
        assertThat(service.renderPdf(100L)).isEmpty();
    }
}
