package github.lms.lemuel.tax.application.service;

import github.lms.lemuel.tax.application.port.in.GetTaxInvoiceUseCase;
import github.lms.lemuel.tax.application.port.out.LoadTaxInvoicePort;
import github.lms.lemuel.tax.application.port.out.RenderTaxInvoicePdfPort;
import github.lms.lemuel.tax.domain.TaxInvoice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 세금계산서 조회 서비스 — 데이터 조회 + PDF 렌더링(관리자/셀러 다운로드).
 */
@Service
@Transactional(readOnly = true)
public class GetTaxInvoiceService implements GetTaxInvoiceUseCase {

    private final LoadTaxInvoicePort loadInvoicePort;
    private final RenderTaxInvoicePdfPort renderPdfPort;

    public GetTaxInvoiceService(LoadTaxInvoicePort loadInvoicePort, RenderTaxInvoicePdfPort renderPdfPort) {
        this.loadInvoicePort = loadInvoicePort;
        this.renderPdfPort = renderPdfPort;
    }

    @Override
    public Optional<TaxInvoice> bySettlementId(Long settlementId) {
        return loadInvoicePort.findBySettlementId(settlementId);
    }

    @Override
    public Optional<byte[]> renderPdf(Long settlementId) {
        return loadInvoicePort.findBySettlementId(settlementId).map(renderPdfPort::render);
    }
}
