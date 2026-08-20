package github.lms.lemuel.report.domain;

import github.lms.lemuel.report.domain.exception.InvalidReportPeriodException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 통계 조회 기간 — 화면이 넘긴 두 날짜가 집계 가능한 구간인지 판정한다.
 *
 * <p>기간 검증을 SQL 앞단에 두는 이유: 상한이 없으면 연도 오타 한 번이 전 기간 스캔이 된다.
 * 직전 기간을 도메인이 계산하는 이유: "전기 대비"의 분모를 화면이 정하면 화면마다 달라진다.
 */
class ReportPeriodTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);

    @Nested
    @DisplayName("생성")
    class Creation {

        @Test
        @DisplayName("시작·종료가 같으면 하루짜리 기간이다")
        void singleDay() {
            ReportPeriod period = ReportPeriod.of(JAN_1, JAN_1);

            assertThat(period.days()).isEqualTo(1);
            assertThat(period.from()).isEqualTo(JAN_1);
            assertThat(period.to()).isEqualTo(JAN_1);
        }

        @Test
        @DisplayName("일수는 양 끝을 포함해 센다")
        void inclusiveDayCount() {
            assertThat(ReportPeriod.of(JAN_1, JAN_31).days()).isEqualTo(31);
        }
    }

    @Nested
    @DisplayName("거절")
    class Rejection {

        @Test
        @DisplayName("시작이 종료보다 뒤면 거절한다")
        void reversedRange() {
            assertThatThrownBy(() -> ReportPeriod.of(JAN_31, JAN_1))
                    .isInstanceOf(InvalidReportPeriodException.class)
                    .hasMessageContaining("시작일");
        }

        @Test
        @DisplayName("시작일이 없으면 거절한다")
        void nullFrom() {
            assertThatThrownBy(() -> ReportPeriod.of(null, JAN_31))
                    .isInstanceOf(InvalidReportPeriodException.class);
        }

        @Test
        @DisplayName("종료일이 없으면 거절한다")
        void nullTo() {
            assertThatThrownBy(() -> ReportPeriod.of(JAN_1, null))
                    .isInstanceOf(InvalidReportPeriodException.class);
        }

        @Test
        @DisplayName("366일까지는 허용한다 — 윤년 1년 조회가 경계다")
        void maxSpanAllowed() {
            assertThat(ReportPeriod.of(JAN_1, JAN_1.plusDays(365)).days()).isEqualTo(366);
        }

        @Test
        @DisplayName("367일부터는 거절한다")
        void overMaxSpanRejected() {
            assertThatThrownBy(() -> ReportPeriod.of(JAN_1, JAN_1.plusDays(366)))
                    .isInstanceOf(InvalidReportPeriodException.class)
                    .hasMessageContaining("366");
        }
    }

    @Nested
    @DisplayName("직전 기간")
    class Previous {

        @Test
        @DisplayName("같은 길이만큼 앞에 붙여 잡는다 — 전기 대비 비교의 분모")
        void sameLengthImmediatelyBefore() {
            ReportPeriod previous = ReportPeriod.of(JAN_1, JAN_31).previous();

            assertThat(previous.to()).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(previous.from()).isEqualTo(LocalDate.of(2025, 12, 1));
            assertThat(previous.days()).isEqualTo(31);
        }

        @Test
        @DisplayName("하루짜리 기간의 직전은 전날 하루다")
        void singleDayPrevious() {
            ReportPeriod previous = ReportPeriod.of(JAN_1, JAN_1).previous();

            assertThat(previous.from()).isEqualTo(LocalDate.of(2025, 12, 31));
            assertThat(previous.to()).isEqualTo(LocalDate.of(2025, 12, 31));
        }

        @Test
        @DisplayName("최대 기간의 직전도 만들 수 있다 — 상한 검증이 내부 파생까지 막으면 안 된다")
        void previousOfMaxSpan() {
            ReportPeriod max = ReportPeriod.of(JAN_1, JAN_1.plusDays(365));

            assertThat(max.previous().days()).isEqualTo(366);
        }
    }
}
