package github.lms.lemuel.settlement.config;

import github.lms.lemuel.settlement.domain.BusinessDayCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.settlement.extra-holidays} 설정이 {@link BusinessDayCalculator} 로 주입·설치되는지 검증.
 *
 * <p>도메인 정적 캘린더는 프로세스 전역이라, 설치 부수효과가 다른 테스트로 새지 않도록 @AfterEach 에서 표준으로 복원한다.
 */
class SettlementCalendarConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SettlementCalendarConfig.class);

    @AfterEach
    void resetProcessDefault() {
        BusinessDayCalculator.installDefault(BusinessDayCalculator.standard());
    }

    @Test
    @DisplayName("설정된 임시공휴일이 캘린더 빈과 설치된 기본 캘린더에 반영된다")
    void extraHolidaysProperty_bindsAndInstalls() {
        runner.withPropertyValues("app.settlement.extra-holidays=2026-04-29,2026-06-03")
                .run(context -> {
                    assertThat(context).hasSingleBean(BusinessDayCalculator.class);
                    BusinessDayCalculator calculator = context.getBean(BusinessDayCalculator.class);

                    assertThat(calculator.extraHolidays())
                            .containsExactlyInAnyOrder(LocalDate.of(2026, 4, 29), LocalDate.of(2026, 6, 3));
                    assertThat(calculator.isBusinessDayOn(LocalDate.of(2026, 4, 29))).isFalse();
                    // 04-28(화) + 1 영업일: 04-29 가 임시공휴일 → 04-30(목)으로 밀린다.
                    assertThat(calculator.addBusinessDaysFrom(LocalDate.of(2026, 4, 28), 1))
                            .isEqualTo(LocalDate.of(2026, 4, 30));
                    // 정적 도메인 경로가 참조하는 기본 캘린더로 설치됐다.
                    assertThat(BusinessDayCalculator.activeDefault()).isSameAs(calculator);
                    assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 4, 29))).isFalse();
                });
    }

    @Test
    @DisplayName("미설정 시 추가 공휴일 없는 표준 캘린더 — 현행 하드코딩 동작 유지(무회귀)")
    void noProperty_usesStandardCalendar() {
        runner.run(context -> {
            BusinessDayCalculator calculator = context.getBean(BusinessDayCalculator.class);
            assertThat(calculator.extraHolidays()).isEmpty();
            assertThat(calculator).isSameAs(BusinessDayCalculator.standard());
            assertThat(calculator.isBusinessDayOn(LocalDate.of(2026, 4, 29))).isTrue();
        });
    }
}
