package github.lms.lemuel.loan.application.port.out;

import github.lms.lemuel.loan.domain.LoanAdvance;

import java.util.List;

public interface LoadLoanPort {

    /** ID 로 단건 조회. 없으면 IllegalArgumentException. */
    LoanAdvance load(Long loanId);

    /** 셀러 단위 조회 (조회 API 용). */
    List<LoanAdvance> findBySeller(Long sellerId);

    /**
     * 셀러의 미상환(DISBURSED) 대출을 FIFO(오래된 순)로 비관적 락 조회 — 상환 차감용.
     * 동시 차감 경합을 직렬화한다.
     */
    List<LoanAdvance> findDisbursedBySellerForUpdate(Long sellerId);
}
