package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.SecuredLoan;

import java.util.List;
import java.util.Optional;

/**
 * 담보/개인신용 대출 조회 아웃바운드 포트.
 */
public interface LoadSecuredLoanPort {

    Optional<SecuredLoan> findById(Long loanId);

    /** 실행·상환 전용 — 비관적 락으로 조회해 동시 이중지급/이중차감을 차단한다. */
    Optional<SecuredLoan> findByIdForUpdate(Long loanId);

    /** 차주 본인 대출 최신순 상위 {@code limit} 건 — 소유권 스코핑용. */
    List<SecuredLoan> findByBorrower(Long borrowerUserId, int limit);

    /** 연체 판정 대상(실행 중·연체 중) 대출 목록. */
    List<SecuredLoan> findRepayable();
}
