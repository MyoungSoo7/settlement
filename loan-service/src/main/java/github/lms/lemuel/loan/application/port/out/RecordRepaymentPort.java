package github.lms.lemuel.loan.application.port.out;

import java.math.BigDecimal;

/**
 * 정산 차감 상환 기록 아웃바운드 포트. settlementId 기준 멱등.
 */
public interface RecordRepaymentPort {

    /** 해당 정산건의 상환이 이미 기록됐는지(멱등 체크). */
    boolean existsForSettlement(long settlementId);

    /** 정산건당 1회 상환 기록(차감 총액). */
    void record(long settlementId, long sellerId, BigDecimal deducted);
}
