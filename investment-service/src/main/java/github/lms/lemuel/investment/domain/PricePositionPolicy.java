package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 시세 위치 판정 정책(R4·R5) — 순수 도메인.
 *
 * <ul>
 *   <li><b>R4 추격 구간</b>: 최신일 기준 종가가 직전일보다 큰 날이 3일 이상 연속이면 chaseRisk
 *       (보합·하락이 나오면 streak 끊김).</li>
 *   <li><b>R5 고점 부근</b>: 최신 종가가 52주(최신일−365일) 창 내 최고 종가의 95% 이상이면 nearHigh.</li>
 * </ul>
 *
 * <p>둘 중 하나라도 참이면 CAUTION — 종목 탈락이 아니라 진입 시점 주의 신호다.
 */
public class PricePositionPolicy {

    private static final int CHASE_STREAK_THRESHOLD = 3;
    private static final BigDecimal NEAR_HIGH_RATIO = new BigDecimal("0.95");
    private static final int FIFTY_TWO_WEEK_DAYS = 365;

    public PricePositionCheck evaluate(List<DailyClose> closes) {
        if (closes == null || closes.isEmpty()) {
            return PricePositionCheck.noData();
        }
        List<DailyClose> sorted = closes.stream()
                .sorted(Comparator.comparing(DailyClose::date))
                .toList();
        DailyClose latest = sorted.getLast();

        int streak = riseStreak(sorted);
        boolean chaseRisk = streak >= CHASE_STREAK_THRESHOLD;

        LocalDate windowStart = latest.date().minusDays(FIFTY_TWO_WEEK_DAYS);
        List<BigDecimal> window = sorted.stream()
                .filter(c -> c.onOrAfter(windowStart))
                .map(DailyClose::close)
                .toList();
        BigDecimal high = window.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal low = window.stream().min(BigDecimal::compareTo).orElseThrow();

        boolean nearHigh = latest.close().compareTo(high.multiply(NEAR_HIGH_RATIO)) >= 0;
        BigDecimal highGapPercent = high.signum() > 0
                ? latest.close().subtract(high)
                        .divide(high, MathContext.DECIMAL64)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP)
                : null;

        PricePositionCheck.Status status = (chaseRisk || nearHigh)
                ? PricePositionCheck.Status.CAUTION
                : PricePositionCheck.Status.OK;
        return new PricePositionCheck(status, latest.date(), latest.close(),
                streak, chaseRisk, high, low, highGapPercent, nearHigh);
    }

    /** 최신일부터 거꾸로 세는 연속 상승 일수 — 직전일 종가보다 큰 동안만 증가. */
    private int riseStreak(List<DailyClose> sorted) {
        int streak = 0;
        for (int i = sorted.size() - 1; i > 0; i--) {
            if (sorted.get(i).roseFrom(sorted.get(i - 1))) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }
}
