package github.lms.lemuel.tax.application.port.out;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoadTaxInvoiceScanPort {

    Optional<TaxInvoiceScan> findById(Long id);

    /** 멱등 키 조회 — 같은 셀러가 같은 파일을 다시 올렸는지. */
    Optional<TaxInvoiceScan> findBySellerIdAndFileHash(Long sellerId, String fileHash);

    /** 관리자 리뷰 큐 — 상태 집합에 드는 스캔을 최신순으로. */
    List<TaxInvoiceScan> findByStatusIn(Collection<TaxInvoiceScanStatus> statuses, int limit);
}
