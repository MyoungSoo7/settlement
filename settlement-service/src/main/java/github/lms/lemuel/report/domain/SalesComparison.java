package github.lms.lemuel.report.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 전기 대비 증감 — 같은 길이의 직전 기간({@link ReportPeriod#previous()})을 분모로 삼는다.
 *
 * <p><b>분모가 0 이면 증감률은 null 이다.</b> 이 한 줄이 이 클래스의 존재 이유다:
 *
 * <ul>
 *   <li>0% 로 답하면 "변화 없음"으로 읽혀 정반대의 사실을 전한다 — 실제로는 0에서 매출이 생긴 것이다.
 *   <li>∞ 나 아주 큰 수로 답하면 화면의 축이 무너진다.
 * </ul>
 *
 * <p>"비교할 직전 값이 없다"는 것은 숫자가 아니라 <b>상태</b>이므로, 그 상태를 null 로 넘겨
 * 화면이 "—"로 그리게 한다. 축(매출·정산액·건수)마다 분모가 다르므로 판정도 축별로 따로 한다.
 *
 * <p>비율 스케일은 소수 4자리(0.0000 = 0%, 1.0000 = +100%)로, 백분율 변환은 화면 몫이다.
 */
public record SalesComparison(CashflowTotals current, CashflowTotals previous) {

    private static final int RATE_SCALE = 4;

    /** 거래액 증감률. 직전 거래액이 0 이면 null. */
    public BigDecimal gmvGrowthRate() {
        return growth(current.gmv(), previous.gmv());
    }

    /** 순정산액 증감률. 직전 순정산액이 0 이면 null. */
    public BigDecimal netGrowthRate() {
        return growth(current.netSettlement(), previous.netSettlement());
    }

    /** 건수 증감률. 직전 건수가 0 이면 null. */
    public BigDecimal countGrowthRate() {
        return growth(BigDecimal.valueOf(current.transactionCount()),
                BigDecimal.valueOf(previous.transactionCount()));
    }

    private static BigDecimal growth(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) {
            return null;
        }
        BigDecimal currentValue = now == null ? BigDecimal.ZERO : now;
        return currentValue.subtract(before).divide(before, RATE_SCALE, RoundingMode.HALF_UP);
    }
}
