package github.lms.lemuel.settlement.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CommissionCalculation(
    BigDecimal paymentAmount,
    BigDecimal commissionRate,
    BigDecimal commissionAmount,
    BigDecimal netAmount
) {
    public static CommissionCalculation calculate(BigDecimal paymentAmount,
                                                   BigDecimal commissionRate) {
        if (paymentAmount == null || paymentAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("결제 금액은 0보다 커야 합니다.");
        }
        if (commissionRate == null || commissionRate.compareTo(BigDecimal.ZERO) < 0
                || commissionRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("수수료율은 0 이상 1 미만이어야 합니다.");
        }
        BigDecimal commission = paymentAmount.multiply(commissionRate)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = paymentAmount.subtract(commission)
                .setScale(2, RoundingMode.HALF_UP);
        return new CommissionCalculation(paymentAmount, commissionRate, commission, net);
    }
}
