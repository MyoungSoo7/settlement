package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDayCalculatorTest {

    @Test
    @DisplayName("addBusinessDays: 평일에 1일 더하기 — 다음 평일")
    void weekday_plus1() {
        // 2026-04-28 화 → 2026-04-29 수
        LocalDate result = BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 4, 28), 1);
        assertThat(result).isEqualTo(LocalDate.of(2026, 4, 29));
    }

    @Test
    @DisplayName("addBusinessDays: 금요일 + 1 → 토일 건너뛰고 월요일")
    void friday_plus1_skipsWeekend() {
        // 2026-05-01 금 → 2026-05-04 월 (5월 5일 어린이날인데 -1 안되니 5/4 정상)
        LocalDate result = BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 5, 1), 1);
        assertThat(result).isEqualTo(LocalDate.of(2026, 5, 4));
    }

    @Test
    @DisplayName("addBusinessDays: 금요일 + 3 → 다음주 수요일")
    void friday_plus3() {
        // 2026-05-01 금 + 3 영업일:
        //   5/4 월(1) → 어린이날(5/5 화) 스킵 → 5/6 수(2) → 5/7 목(3)
        LocalDate result = BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 5, 1), 3);
        assertThat(result).isEqualTo(LocalDate.of(2026, 5, 7));
    }

    @Test
    @DisplayName("addBusinessDays: 토요일 + 1 → 월요일")
    void saturday_plus1() {
        // 2026-05-02 토 → 5/4 월(1)
        LocalDate result = BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 5, 2), 1);
        assertThat(result).isEqualTo(LocalDate.of(2026, 5, 4));
    }

    @Test
    @DisplayName("addBusinessDays: n=0 — 시작일 영업일이면 그대로, 아니면 다음 영업일")
    void zero_days() {
        // 평일 → 그대로
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 4, 28), 0))
                .isEqualTo(LocalDate.of(2026, 4, 28));
        // 토요일 → 월요일
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 5, 2), 0))
                .isEqualTo(LocalDate.of(2026, 5, 4));
        // 어린이날 (2026-05-05 화 공휴일) → 다음 영업일 5/6 수
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 5, 5), 0))
                .isEqualTo(LocalDate.of(2026, 5, 6));
    }

    @Test
    @DisplayName("isBusinessDay: 토/일/공휴일 false")
    void isBusinessDay() {
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 4, 28))).isTrue();   // 화
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 5, 2))).isFalse();    // 토
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 5, 3))).isFalse();    // 일
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 5, 5))).isFalse();    // 어린이날
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 1, 1))).isFalse();    // 신정
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse();  // 성탄절
    }

    @Test
    @DisplayName("addBusinessDays: 음수 거부")
    void negative() {
        assertThatThrownBy(() -> BusinessDayCalculator.addBusinessDays(LocalDate.now(), -1))
                .isInstanceOf(SettlementInvariantViolationException.class);
    }
}
