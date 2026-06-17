package github.lms.lemuel.settlement.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 선정산 대출 차감액(정산건별) 기록·조회 아웃바운드 포트.
 * loan 의 LoanRepaymentApplied 를 받아 settlement 측에 차감액을 보존한다 (settlement_id 멱등).
 */
public interface RecordLoanDeductionPort {

    /** 정산건별 차감액 기록 (settlement_id PK → 멱등 UPSERT). */
    void record(long settlementId, long sellerId, BigDecimal deducted);

    /** 정산건의 대출 차감액. 없으면 empty (= 차감 0). */
    Optional<BigDecimal> findDeduction(long settlementId);
}
