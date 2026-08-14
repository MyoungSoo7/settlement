package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;

import java.util.List;
import java.util.Optional;

public interface GetTaxInvoiceScanUseCase {

    Optional<TaxInvoiceScan> byId(Long scanId);

    /** 관리자 리뷰 큐 — 상태별 조회. */
    List<TaxInvoiceScan> byStatus(TaxInvoiceScanStatus status, int limit);
}
