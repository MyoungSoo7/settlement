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

    /**
     * 가용 재원이 요청액을 충당할 수 있는지("가용재원 초과 투자 금지" 불변식의 도메인 판정).
     * 신청·집행 서비스가 이 판정을 공유해 {@code available < requested} 를 인라인으로 흩뿌리지 않게 한다.
     */
    public boolean covers(BigDecimal requested) {
        return available.compareTo(requested) >= 0;
    }
}
