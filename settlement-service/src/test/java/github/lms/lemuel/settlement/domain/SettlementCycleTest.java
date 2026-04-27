package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementCycleTest {

    @Test
    @DisplayName("DAILY — 다음 날짜로 정산")
    void dailyIsNextDay() {
        LocalDate payment = LocalDate.of(2026, 4, 23); // 목
        assertThat(SettlementCycle.DAILY.resolveSettlementDate(payment))
                .isEqualTo(LocalDate.of(2026, 4, 24));
    }

    @Test
    @DisplayName("WEEKLY_MON — 다음 월요일")
    void weeklyMondayFromMidweek() {
        LocalDate payment = LocalDate.of(2026, 4, 23); // 목
        // 다음 월요일은 4/27
        assertThat(SettlementCycle.WEEKLY_MON.resolveSettlementDate(payment))
                .isEqualTo(LocalDate.of(2026, 4, 27));
    }

    @Test
    @DisplayName("WEEKLY_MON — 이미 월요일일 때 다음 주 월요일")
    void weeklyMondayFromMondayGoesToNextWeek() {
        LocalDate monday = LocalDate.of(2026, 4, 20); // 월
        assertThat(SettlementCycle.WEEKLY_MON.resolveSettlementDate(monday))
                .isEqualTo(LocalDate.of(2026, 4, 27));
    }

    @Test
    @DisplayName("MONTHLY_LAST — 같은 달의 마지막 날")
    void monthlyLastDay() {
        LocalDate payment = LocalDate.of(2026, 4, 10);
        assertThat(SettlementCycle.MONTHLY_LAST.resolveSettlementDate(payment))
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("MONTHLY_LAST — 2월 윤년 처리")
    void monthlyLastDayLeapYearFeb() {
        // 2024년은 윤년 — 2/29
        LocalDate feb = LocalDate.of(2024, 2, 15);
        assertThat(SettlementCycle.MONTHLY_LAST.resolveSettlementDate(feb))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    @DisplayName("fromStringOrDefault — 잘못된 값은 DAILY 로 fallback")
    void fromStringFallback() {
        assertThat(SettlementCycle.fromStringOrDefault(null)).isEqualTo(SettlementCycle.DAILY);
        assertThat(SettlementCycle.fromStringOrDefault("garbage")).isEqualTo(SettlementCycle.DAILY);
        assertThat(SettlementCycle.fromStringOrDefault("weekly_mon")).isEqualTo(SettlementCycle.WEEKLY_MON);
    }
}
