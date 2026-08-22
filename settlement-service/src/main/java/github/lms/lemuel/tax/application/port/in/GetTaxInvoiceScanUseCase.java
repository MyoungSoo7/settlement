package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.scan.TaxInvoiceScan;
import github.lms.lemuel.tax.domain.scan.TaxInvoiceScanStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GetTaxInvoiceScanUseCase {

    Optional<TaxInvoiceScan> byId(Long scanId);

    /**
     * 관리자 리뷰 큐 — <b>여러 상태를 한 번에</b> 조회한다(최신순).
     *
     * <p>사람 손이 필요한 상태는 하나가 아니다: 저신뢰 보류(EXTRACTED)·금액 불일치(MISMATCHED)·
     * 미매칭(UNMATCHED). 화면을 상태별로 쪼개 놓으면 한 곳만 보다가 나머지에 쌓인 건을 놓친다.
     */
    List<TaxInvoiceScan> byStatuses(Collection<TaxInvoiceScanStatus> statuses, int limit);
}
