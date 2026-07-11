package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PricePositionPolicy — R4(3거래일 연속 상승 추격 구간)·R5(52주 고점 −5% 이내) 판정 검증.
 */
class PricePositionPolicyTest {

    private static final LocalDate LATEST = LocalDate.of(2026, 7, 10);

    private final PricePositionPolicy policy = new PricePositionPolicy();

    private static DailyClose close(int daysAgo, String price) {
        return new DailyClose(LATEST.minusDays(daysAgo), new BigDecimal(price));
    }

    @Test
    @DisplayName("시세가 없으면 NO_DATA — 매매계획 산정 불가(hasQuote false)")
    void noData() {
        PricePositionCheck check = policy.evaluate(List.of());

        assertThat(check.status()).isEqualTo(PricePositionCheck.Status.NO_DATA);
        assertThat(check.hasQuote()).isFalse();
    }

    @Test
    @DisplayName("3거래일 연속 상승이면 chaseRisk + CAUTION (R4)")
    void chaseRiskOnThreeDayRiseStreak() {
        PricePositionCheck check = policy.evaluate(List.of(
                close(3, "100000"), close(2, "101000"), close(1, "102000"), close(0, "103000")));

        assertThat(check.riseStreakDays()).isEqualTo(3);
        assertThat(check.chaseRisk()).isTrue();
        assertThat(check.status()).isEqualTo(PricePositionCheck.Status.CAUTION);
        assertThat(check.latestClose()).isEqualByComparingTo("103000");
        assertThat(check.baseDate()).isEqualTo(LATEST);
    }

    @Test
    @DisplayName("연속 상승 2일 이하 + 고점에서 멀면 OK — 갭 퍼센트는 음수로 계산된다")
    void okWhenNoChaseAndFarFromHigh() {
        PricePositionCheck check = policy.evaluate(List.of(
                close(2, "100000"), close(1, "50000"), close(0, "50100")));

        assertThat(check.riseStreakDays()).isEqualTo(1);
        assertThat(check.chaseRisk()).isFalse();
        assertThat(check.nearHigh()).isFalse();
        assertThat(check.status()).isEqualTo(PricePositionCheck.Status.OK);
        assertThat(check.fiftyTwoWeekHigh()).isEqualByComparingTo("100000");
        assertThat(check.fiftyTwoWeekLow()).isEqualByComparingTo("50000");
        assertThat(check.highGapPercent()).isEqualByComparingTo("-49.90");
    }

    @Test
    @DisplayName("52주 고점은 365일 창 안에서만 계산한다 — 그보다 오래된 고점은 제외 (R5)")
    void fiftyTwoWeekWindowExcludesOldHighs() {
        PricePositionCheck check = policy.evaluate(List.of(
                close(400, "200000"), // 창 밖 — 고점 계산에서 제외돼야 한다
                close(2, "100000"), close(1, "99000"), close(0, "99500")));

        assertThat(check.fiftyTwoWeekHigh()).isEqualByComparingTo("100000");
        assertThat(check.nearHigh()).isTrue(); // 99,500 ≥ 100,000×0.95
        assertThat(check.chaseRisk()).isFalse();
        assertThat(check.status()).isEqualTo(PricePositionCheck.Status.CAUTION);
    }

    @Test
    @DisplayName("보합(동일 종가)은 상승 streak 을 끊는다")
    void flatCloseBreaksStreak() {
        PricePositionCheck check = policy.evaluate(List.of(
                close(2, "100"), close(1, "100"), close(0, "100")));

        assertThat(check.riseStreakDays()).isZero();
        assertThat(check.chaseRisk()).isFalse();
    }
}
