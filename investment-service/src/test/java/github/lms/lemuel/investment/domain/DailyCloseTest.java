package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DailyClose — 종가 1점이 스스로 답하는 도메인 질문(직전일 대비 상승 여부·기간 창 포함 여부) 검증.
 */
class DailyCloseTest {

    private static final LocalDate DAY = LocalDate.of(2026, 7, 10);

    private static DailyClose close(LocalDate date, String price) {
        return new DailyClose(date, new BigDecimal(price));
    }

    @Test
    @DisplayName("종가가 직전 점보다 크면 roseFrom 참")
    void roseWhenHigher() {
        assertThat(close(DAY, "101").roseFrom(close(DAY.minusDays(1), "100"))).isTrue();
    }

    @Test
    @DisplayName("보합(동일 종가)은 상승이 아니다 — roseFrom 거짓")
    void flatIsNotRise() {
        assertThat(close(DAY, "100").roseFrom(close(DAY.minusDays(1), "100"))).isFalse();
    }

    @Test
    @DisplayName("종가가 직전 점보다 작으면 roseFrom 거짓")
    void fallIsNotRise() {
        assertThat(close(DAY, "99").roseFrom(close(DAY.minusDays(1), "100"))).isFalse();
    }

    @Test
    @DisplayName("기준일 당일·이후는 onOrAfter 참, 이전은 거짓")
    void onOrAfterBoundary() {
        assertThat(close(DAY, "100").onOrAfter(DAY)).isTrue();
        assertThat(close(DAY, "100").onOrAfter(DAY.minusDays(1))).isTrue();
        assertThat(close(DAY, "100").onOrAfter(DAY.plusDays(1))).isFalse();
    }
}
