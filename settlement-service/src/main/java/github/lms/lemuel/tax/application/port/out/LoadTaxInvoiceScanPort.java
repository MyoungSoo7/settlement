package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;

import java.util.List;
import java.util.Optional;

public interface LoadTaxInvoiceScanPort {

    Optional<TaxInvoiceScan> findById(Long id);

    /** 멱등 키 조회 — 같은 셀러가 같은 파일을 다시 올렸는지. */
    Optional<TaxInvoiceScan> findBySellerIdAndFileHash(Long sellerId, String fileHash);

    /** 관리자 리뷰 큐 — 상태별 최신순. */
    List<TaxInvoiceScan> findByStatus(TaxInvoiceScanStatus status, int limit);
}
