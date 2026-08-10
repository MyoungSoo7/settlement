package github.lms.lemuel.deposit.application.port.in;

import java.math.BigDecimal;

/**
 * 셀러 예치금 입금 유스케이스.
 * settlement.confirmed 이벤트 소비 시 호출된다.
 */
public interface CreditDepositUseCase {

    /**
     * @param sellerId     셀러 ID
     * @param amount       입금액 (양수)
     * @param referenceId  정산 ID (멱등 키)
     * @param referenceType "SETTLEMENT"
     */
    void credit(Long sellerId, BigDecimal amount, String referenceId, String referenceType);
}
