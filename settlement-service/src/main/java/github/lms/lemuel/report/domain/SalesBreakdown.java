package github.lms.lemuel.report.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 축 하나에 대한 매출 구성 — 구간 목록에 정렬과 구성비를 입혀 화면이 그대로 그릴 수 있게 만든다.
 *
 * <p>정렬을 도메인이 잡는 이유: SQL 의 {@code ORDER BY} 에 맡기면 축을 하나 늘릴 때마다 순서
 * 규칙이 따로 생기고, 실행계획이 바뀌면 동점 구간의 순서가 흔들린다.
 *
 * <p>구성비를 도메인이 계산하는 이유: 화면에서 나누면 반올림 규칙이 화면 수만큼 생긴다.
 * 여기서는 소수 둘째 자리 반올림 하나로 고정한다 — 그래서 합이 정확히 100.00 이 아닐 수 있고
 * (33.33 × 3 = 99.99), 그건 정상이다. 억지로 100 을 맞추려 잔여를 특정 구간에 몰아주면
 * 그 구간만 실제와 다른 값을 갖게 된다.
 */
public record SalesBreakdown(List<SalesShare> shares, long totalTransactionCount, BigDecimal totalGmv) {

    private static final int PERCENT_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public static SalesBreakdown from(List<SalesSlice> slices) {
        long totalCount = 0L;
        BigDecimal totalGmv = BigDecimal.ZERO;
        for (SalesSlice slice : slices) {
            totalCount += slice.transactionCount();
            totalGmv = totalGmv.add(slice.gmv());
        }

        BigDecimal denominator = totalGmv;
        List<SalesShare> shares = slices.stream()
                .sorted(Comparator.comparing(SalesSlice::gmv).reversed()
                        // 동점 구간의 순서가 실행계획에 따라 흔들리지 않도록 라벨로 확정한다.
                        .thenComparing(SalesSlice::label))
                .map(slice -> SalesShare.of(slice, percentOf(slice.gmv(), denominator)))
                .toList();

        return new SalesBreakdown(shares, totalCount, totalGmv);
    }

    /** 분모가 0 이면 0 을 준다 — 전 구간이 0원인 기간에서 나눗셈으로 터지지 않는다. */
    private static BigDecimal percentOf(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.UNNECESSARY);
        }
        return amount.multiply(HUNDRED).divide(total, PERCENT_SCALE, RoundingMode.HALF_UP);
    }
}
