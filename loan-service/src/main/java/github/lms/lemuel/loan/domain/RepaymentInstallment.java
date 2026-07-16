package github.lms.lemuel.loan.domain;

import java.math.BigDecimal;

/**
 * 상환 스케줄의 한 회차. 전 금액은 원(KRW) 단위 정수 스케일 {@link BigDecimal}(라운딩 HALF_UP).
 *
 * @param installmentNo    회차(1-base)
 * @param principalPortion 이번 회차 납입 원금
 * @param interest         이번 회차 이자(직전 잔액 × 월이율)
 * @param payment          이번 회차 총 납입액(= principalPortion + interest)
 * @param remainingBalance 이번 회차 상환 후 남은 원금 잔액
 */
public record RepaymentInstallment(
        int installmentNo,
        BigDecimal principalPortion,
        BigDecimal interest,
        BigDecimal payment,
        BigDecimal remainingBalance) {
}
