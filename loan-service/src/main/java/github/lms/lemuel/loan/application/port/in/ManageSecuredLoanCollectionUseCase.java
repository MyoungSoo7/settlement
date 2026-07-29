package github.lms.lemuel.loan.application.port.in;

import github.lms.lemuel.loan.domain.SecuredLoan;

/**
 * 담보/개인신용 대출 연체 관리 인바운드 포트(운영자·배치 경로).
 */
public interface ManageSecuredLoanCollectionUseCase {

    /** 회차 미납 → 연체 진입. */
    SecuredLoan markOverdue(Long loanId);

    /**
     * 기한이익상실 — 잔여 원금 전액을 즉시 청구한다. 연체를 거친 대출에만 가능하며,
     * 도메인 상태머신이 {@code DISBURSED → DEFAULTED} 직행을 막는다.
     */
    SecuredLoan accelerate(Long loanId);
}
