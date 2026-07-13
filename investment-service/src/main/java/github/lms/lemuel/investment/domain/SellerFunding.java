package github.lms.lemuel.investment.domain;

import github.lms.lemuel.common.money.Money;

import java.math.BigDecimal;

/**
 * 셀러의 투자 가용 재원 스냅샷(순수 POJO).
 *
 * <pre>
 * available = confirmedTotal − investedTotal(집행 완료된 EXECUTED 투자 합)
 * </pre>
 */
public record SellerFunding(Long sellerId, BigDecimal confirmedTotal, BigDecimal investedTotal, BigDecimal available) {

    public static SellerFunding of(Long sellerId, BigDecimal confirmedTotal, BigDecimal investedTotal) {
        BigDecimal confirmed = confirmedTotal == null ? BigDecimal.ZERO : confirmedTotal;
        BigDecimal invested = investedTotal == null ? BigDecimal.ZERO : investedTotal;
        BigDecimal available = Money.of(confirmed).minus(Money.of(invested)).toBigDecimal();
        return new SellerFunding(sellerId, confirmed, invested, available);
    }
}
