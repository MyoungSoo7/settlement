package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.LoanAdvance;

/**
 * 대출 실행(선지급) 인바운드 포트. 승인 + 담보 재검증 + 실행 + 선지급 이벤트 발행.
 */
public interface DisburseLoanUseCase {
    LoanAdvance disburse(Long loanId);
}
