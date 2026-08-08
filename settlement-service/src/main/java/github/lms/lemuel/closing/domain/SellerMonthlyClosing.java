package github.lms.lemuel.closing.domain;

import github.lms.lemuel.closing.domain.exception.ClosingInvariantViolationException;
import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * 셀러 월 정산 마트 행 — 정보계 마감의 최소 집계 단위 (기간 × 셀러).
 *
 * <p>DONE 정산만 집계한 확정 실적이다: 건수, 총매출(payment), 환불, 수수료, 홀드백, 실지급(net).
 * 불변 값 객체 — 생성 후 수정 없음, 재마감 시 기간 단위로 통째로 교체(replace)된다.
 */
public class SellerMonthlyClosing {

    private final YearMonth period;
    private final Long sellerId;
    private final long settlementCount;
    private final BigDecimal grossAmount;
    private final BigDecimal refundedAmount;
    private final BigDecimal commissionAmount;
    private final BigDecimal holdbackAmount;
    private final BigDecimal netAmount;

    private SellerMonthlyClosing(YearMonth period, Long sellerId, long settlementCount,
                                 BigDecimal grossAmount, BigDecimal refundedAmount,
                                 BigDecimal commissionAmount, BigDecimal holdbackAmount,
                                 BigDecimal netAmount) {
        this.period = period;
        this.sellerId = sellerId;
        this.settlementCount = settlementCount;
        this.grossAmount = grossAmount;
        this.refundedAmount = refundedAmount;
        this.commissionAmount = commissionAmount;
        this.holdbackAmount = holdbackAmount;
        this.netAmount = netAmount;
    }

    public static SellerMonthlyClosing of(YearMonth period, Long sellerId, long settlementCount,
                                          BigDecimal grossAmount, BigDecimal refundedAmount,
                                          BigDecimal commissionAmount, BigDecimal holdbackAmount,
                                          BigDecimal netAmount) {
        if (period == null) {
            throw new ClosingInvariantViolationException("period 필수");
        }
        if (sellerId == null || sellerId <= 0) {
            throw new ClosingInvariantViolationException("sellerId 필수(양수): " + sellerId);
        }
        if (settlementCount <= 0) {
            throw new ClosingInvariantViolationException(
                    "집계 행은 정산 1건 이상이어야 합니다: " + settlementCount);
        }
        return new SellerMonthlyClosing(period, sellerId, settlementCount,
                requireNonNegative(grossAmount, "grossAmount"),
                requireNonNegative(refundedAmount, "refundedAmount"),
                requireNonNegative(commissionAmount, "commissionAmount"),
                requireNonNegative(holdbackAmount, "holdbackAmount"),
                requireNonNegative(netAmount, "netAmount"));
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

    // ========== Getters ==========

    public YearMonth getPeriod() {
        return period;
    }

    /** 영속·표현 경계용 "YYYY-MM" 문자열. */
    public String getPeriodYm() {
        return period.toString();
    }

    public Long getSellerId() {
        return sellerId;
    }

    public long getSettlementCount() {
        return settlementCount;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public BigDecimal getHoldbackAmount() {
        return holdbackAmount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }
}
