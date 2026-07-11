package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.CorporateLoan;

/**
 * 기업 신용대출 저장 아웃바운드 포트.
 */
public interface SaveCorporateLoanPort {
    CorporateLoan save(CorporateLoan loan);
}
