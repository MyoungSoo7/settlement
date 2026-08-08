package github.lms.lemuel.closing.domain;

import github.lms.lemuel.closing.domain.exception.ClosingInvariantViolationException;
import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * 월마감 합계 스냅샷 — 마트 셀러 행들의 총매출/환불/수수료/홀드백/실지급 합.
 *
 * <p>불변 값 객체. 모든 합계는 0 이상(집계 원천이 전부 비음수 컬럼)이며, 음수·null 은 생성 시점에 거부한다.
 */
public record ClosingTotals(BigDecimal grossAmount, BigDecimal refundedAmount,
                            BigDecimal commissionAmount, BigDecimal holdbackAmount,
                            BigDecimal netAmount) {

    public static ClosingTotals of(BigDecimal gross, BigDecimal refunded, BigDecimal commission,
                                   BigDecimal holdback, BigDecimal net) {
        return new ClosingTotals(
                requireNonNegative(gross, "grossAmount"),
                requireNonNegative(refunded, "refundedAmount"),
                requireNonNegative(commission, "commissionAmount"),
                requireNonNegative(holdback, "holdbackAmount"),
                requireNonNegative(net, "netAmount"));
    }

    /** 셀러 월 집계 행들을 합산한다 — 빈 목록이면 전부 0원(거래 없는 월 마감). */
    public static ClosingTotals sumOf(List<SellerMonthlyClosing> rows) {
        Money gross = Money.won(0);
        Money refunded = Money.won(0);
        Money commission = Money.won(0);
        Money holdback = Money.won(0);
        Money net = Money.won(0);
        for (SellerMonthlyClosing row : rows) {
            gross = gross.plus(Money.of(row.getGrossAmount()));
            refunded = refunded.plus(Money.of(row.getRefundedAmount()));
            commission = commission.plus(Money.of(row.getCommissionAmount()));
            holdback = holdback.plus(Money.of(row.getHoldbackAmount()));
            net = net.plus(Money.of(row.getNetAmount()));
        }
        return of(gross.toBigDecimal(), refunded.toBigDecimal(), commission.toBigDecimal(),
                holdback.toBigDecimal(), net.toBigDecimal());
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null) {
            throw new ClosingInvariantViolationException(field + " 필수");
        }
        Money money = Money.of(value);
        if (money.isNegative()) {
            throw new ClosingInvariantViolationException(field + " 는 음수일 수 없습니다: " + value);
        }
        return money.toBigDecimal();
    }
}
