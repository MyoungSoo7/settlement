package github.lms.lemuel.deposit.application.port.in;

import java.math.BigDecimal;

/**
 * 셀러 예치금 출금 유스케이스.
 * payout.completed 이벤트 소비 시 호출된다.
 */
public interface DebitDepositUseCase {

    /**
     * @param sellerId     셀러 ID
     * @param amount       출금액 (양수)
     * @param referenceId  payout ID (멱등 키)
     * @param referenceType "PAYOUT"
     */
    void debit(Long sellerId, BigDecimal amount, String referenceId, String referenceType);
}
