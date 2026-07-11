package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.CorporateLoan;

/**
 * 기업 신용대출 실행(지급) 인바운드 포트. 승인 → 실행 → 복식부기 전표 + 실행 이벤트 발행.
 */
public interface DisburseCorporateLoanUseCase {
    CorporateLoan disburse(Long loanId);
}
