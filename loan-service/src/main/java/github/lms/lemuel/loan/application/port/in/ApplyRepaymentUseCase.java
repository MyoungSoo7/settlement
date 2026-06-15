package github.lms.lemuel.loan.application.port.in;

import java.math.BigDecimal;

/**
 * 정산 확정(SettlementConfirmed) 에 따른 상환 차감 인바운드 포트.
 */
public interface ApplyRepaymentUseCase {

    void apply(ApplyRepaymentCommand command);

    /**
     * @param settlementId 확정된 정산 ID (차감 멱등 키)
     * @param sellerId     셀러
     * @param amount       확정 정산금 (이 범위 내에서 미상환 대출을 차감)
     */
    record ApplyRepaymentCommand(long settlementId, long sellerId, BigDecimal amount) {
    }
}
