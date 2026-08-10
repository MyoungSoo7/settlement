package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkforceHistoryTest {

    private static final String NAME = "주식회사에고이즘";
    private static final String PREFIX = "866759";

    private CompanyWorkforce snapshot(YearMonth month, int headcount, String billed) {
        return new CompanyWorkforce(NAME, PREFIX, "525101", "전자상거래 소매업",
                "서울특별시 성동구 연무장19길", month, headcount, new BigDecimal(billed));
    }

    @Test
    @DisplayName("입력 순서와 무관하게 월 오름차순으로 정렬한다")
    void sortsBySnapshotMonthAscending() {
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 6), 60, "18000000"),
                snapshot(YearMonth.of(2026, 4), 50, "16406250"),
                snapshot(YearMonth.of(2026, 5), 55, "17000000")));

        assertThat(history.points()).extracting(WorkforceTrendPoint::month)
                .containsExactly(YearMonth.of(2026, 4), YearMonth.of(2026, 5), YearMonth.of(2026, 6));
    }

    @Test
    @DisplayName("연속 인접 월은 전월 대비 증감(명·%·원·%)을 HALF_UP 으로 계산한다")
    void computesMonthOverMonthChangeForAdjacentMonths() {
        // 2026-05: 50명, 추정연봉 (16,406,250×12)/(50×0.095) = 41,447,368
        // 2026-06: 60명, 추정연봉 (18,000,000×12)/(60×0.095) = 37,894,737
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 5), 50, "16406250"),
                snapshot(YearMonth.of(2026, 6), 60, "18000000")));

        WorkforceTrendPoint latest = history.points().get(1);
        assertThat(latest.headcountChange()).isEqualByComparingTo("10");
        assertThat(latest.headcountChangeRate()).isEqualByComparingTo("20.00");
        assertThat(latest.salaryChange()).isEqualByComparingTo("-3552631");
        // -3,552,631 / 41,447,368 × 100 = -8.5714… → HALF_UP 2자리 = -8.57
        assertThat(latest.salaryChangeRate()).isEqualByComparingTo("-8.57");
    }

    @Test
    @DisplayName("첫 월의 증감은 전부 null 이다")
    void firstPointHasNoChange() {
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 5), 50, "16406250"),
                snapshot(YearMonth.of(2026, 6), 60, "18000000")));

        WorkforceTrendPoint first = history.points().get(0);
        assertThat(first.headcountChange()).isNull();
        assertThat(first.headcountChangeRate()).isNull();
        assertThat(first.salaryChange()).isNull();
        assertThat(first.salaryChangeRate()).isNull();
    }

    @Test
    @DisplayName("결측 월을 사이에 둔 스냅샷 간 증감은 계산하지 않는다 (보간 금지)")
    void noChangeAcrossGap() {
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 4), 50, "16406250"),
                snapshot(YearMonth.of(2026, 6), 60, "18000000")));

        WorkforceTrendPoint latest = history.points().get(1);
        assertThat(latest.headcountChange()).isNull();
        assertThat(latest.headcountChangeRate()).isNull();
        assertThat(latest.salaryChange()).isNull();
        assertThat(latest.salaryChangeRate()).isNull();
    }

    @Test
    @DisplayName("단월(길이 1) 시리즈도 성립한다 — 증감 전부 null (AC-4)")
    void singleMonthSeries() {
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 6), 50, "16406250")));

        assertThat(history.points()).hasSize(1);
        WorkforceTrendPoint only = history.points().get(0);
        assertThat(only.headcount()).isEqualTo(50);
        assertThat(only.estimatedAnnualSalary()).isEqualByComparingTo("41447368");
        assertThat(only.headcountChange()).isNull();
        assertThat(only.salaryChangeRate()).isNull();
    }

    @Test
    @DisplayName("전월 인원 0 이면 인원 증감률은 null, 추정연봉 증감은 null (분모·전월값 부재)")
    void zeroPreviousHeadcount() {
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 5), 0, "0"),
                snapshot(YearMonth.of(2026, 6), 5, "1500000")));

        WorkforceTrendPoint latest = history.points().get(1);
        assertThat(latest.headcountChange()).isEqualByComparingTo("5");
        assertThat(latest.headcountChangeRate()).isNull();
        assertThat(latest.salaryChange()).isNull();
        assertThat(latest.salaryChangeRate()).isNull();
    }

    @Test
    @DisplayName("추정연봉 없는 월(인원 0)의 salary 필드는 null 이고 상한 플래그는 false")
    void pointWithoutSalary() {
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 6), 0, "0")));

        WorkforceTrendPoint only = history.points().get(0);
        assertThat(only.estimatedAnnualSalary()).isNull();
        assertThat(only.salaryCapReached()).isFalse();
    }

    @Test
    @DisplayName("서로 다른 사업장 키가 섞이면 거부한다 (AC-3 시리즈 키 고정)")
    void rejectsMixedSeriesKey() {
        CompanyWorkforce other = new CompanyWorkforce("다른회사", PREFIX, null, "업종",
                "주소", YearMonth.of(2026, 6), 3, new BigDecimal("1000000"));

        assertThatThrownBy(() -> WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 5), 50, "16406250"), other)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("단일 사업장");
    }

    @Test
    @DisplayName("같은 월이 중복되면 거부한다 (UNIQUE 위반 데이터 방어)")
    void rejectsDuplicateMonth() {
        assertThatThrownBy(() -> WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 6), 50, "16406250"),
                snapshot(YearMonth.of(2026, 6), 60, "18000000"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("빈 시리즈는 거부한다")
    void rejectsEmptySeries() {
        assertThatThrownBy(() -> WorkforceHistory.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("각 월의 상한 도달 플래그는 스냅샷 판정을 그대로 나른다")
    void carriesSalaryCapFlagPerMonth() {
        // 2026-06 상한 월 6,370,000 → 연 76,440,000. 1명·고지 605,150 → 추정연봉 76,440,000 = 상한
        WorkforceHistory history = WorkforceHistory.of(List.of(
                snapshot(YearMonth.of(2026, 6), 1, "605150")));

        assertThat(history.points().get(0).salaryCapReached()).isTrue();
    }
}
