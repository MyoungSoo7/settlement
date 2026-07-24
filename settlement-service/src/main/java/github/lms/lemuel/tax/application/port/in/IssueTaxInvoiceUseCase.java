package github.lms.lemuel.tax.application.port.in;

import github.lms.lemuel.tax.domain.TaxInvoice;

import java.util.Optional;

/**
 * 세금계산서 발행 유스케이스 — 정산 1건에 대해 플랫폼→셀러 수수료 세금계산서를 발행한다(멱등).
 */
public interface IssueTaxInvoiceUseCase {

    /**
     * 세금계산서를 발행한다. 이미 발행됐거나(멱등) 미등록/미확정으로 보류면 빈 Optional 을 반환한다.
     */
    Optional<TaxInvoice> issueForSettlement(Long settlementId, Long sellerId);
}
