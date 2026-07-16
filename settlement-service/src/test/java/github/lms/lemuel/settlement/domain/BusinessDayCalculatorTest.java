package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDayCalculatorTest {

    /** 정적 도메인 경로가 참조하는 프로세스 기본 캘린더를 표준으로 되돌린다 — installDefault 를 쓰는 테스트 격리. */
    @AfterEach
    void resetProcessDefault() {
        BusinessDayCalculator.installDefault(BusinessDayCalculator.standard());
    }

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

    // ── 2026 음력 명절·대체공휴일 (연도별 하드코딩 상수) ──────────────────────────

    @Test
    @DisplayName("isBusinessDay: 2026 설날 연휴(02-16~18)는 비영업일")
    void isBusinessDay_seollal2026() {
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 2, 16))).isFalse(); // 설 전날
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 2, 17))).isFalse(); // 설날
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 2, 18))).isFalse(); // 설 다음날
    }

    @Test
    @DisplayName("isBusinessDay: 2026 추석 연휴(09-24~26)+대체공휴일(09-28)은 비영업일")
    void isBusinessDay_chuseok2026() {
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 9, 24))).isFalse(); // 추석 전날
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 9, 25))).isFalse(); // 추석
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 9, 26))).isFalse(); // 추석 다음날(토)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 9, 28))).isFalse(); // 대체공휴일(월)
    }

    @Test
    @DisplayName("isBusinessDay: 2026 대체공휴일(삼일절·부처님·광복절·개천절)은 비영업일")
    void isBusinessDay_substituteHolidays2026() {
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 3, 2))).isFalse();  // 삼일절 대체(월)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 5, 25))).isFalse(); // 부처님오신날 대체(월)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 8, 17))).isFalse(); // 광복절 대체(월)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2026, 10, 5))).isFalse(); // 개천절 대체(월)
    }

    @Test
    @DisplayName("addBusinessDays: 설날 연휴를 통째로 건너뛴다 (연휴 경계)")
    void addBusinessDays_skipsSeollalRun() {
        // 2026-02-13(금) + 1 영업일: 토·일·설연휴(16 월·17 화·18 수) 모두 스킵 → 02-19(목)
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 2, 13), 1))
                .isEqualTo(LocalDate.of(2026, 2, 19));
    }

    @Test
    @DisplayName("addBusinessDays: 추석 연휴+대체공휴일을 통째로 건너뛴다 (연휴 경계)")
    void addBusinessDays_skipsChuseokRun() {
        // 2026-09-23(수) + 1 영업일: 24 목·25 금(추석)·26 토·27 일·28 월(대체) 모두 스킵 → 09-29(화)
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 9, 23), 1))
                .isEqualTo(LocalDate.of(2026, 9, 29));
    }

    // ── 2027~2030 다개년 음력 명절·대체공휴일 경계 ─────────────────────────────────

    @Test
    @DisplayName("isBusinessDay: 2027 설날 연휴+대체(02-06~10)는 비영업일 — 토·일 이틀 중첩")
    void isBusinessDay_seollal2027() {
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2027, 2, 6))).isFalse();  // 설 전날(토)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2027, 2, 7))).isFalse();  // 설날(일)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2027, 2, 8))).isFalse();  // 설 다음날(월)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2027, 2, 9))).isFalse();  // 대체(화)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2027, 2, 10))).isFalse(); // 대체(수)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2027, 2, 11))).isTrue();  // 연휴 후 첫 영업일(목)
    }

    @Test
    @DisplayName("addBusinessDays: 2027 설날 연휴를 통째로 건너뛴다 (연휴 직전 결제 → 첫 영업일)")
    void addBusinessDays_skipsSeollal2027Run() {
        // 2027-02-05(금) + 1 영업일: 토·일·설연휴(6~8)·대체(9 화·10 수) 모두 스킵 → 02-11(목)
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2027, 2, 5), 1))
                .isEqualTo(LocalDate.of(2027, 2, 11));
    }

    @Test
    @DisplayName("isBusinessDay: 2028 추석 연휴+중첩 대체(10-02~05)는 비영업일 — 추석(10-03)이 개천절과 같은 날")
    void isBusinessDay_chuseok2028() {
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2028, 10, 2))).isFalse(); // 추석 전날(월)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2028, 10, 3))).isFalse(); // 추석=개천절(화)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2028, 10, 4))).isFalse(); // 추석 다음날(수)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2028, 10, 5))).isFalse(); // 중첩 대체(목)
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2028, 10, 6))).isTrue();  // 연휴 후 첫 영업일(금)
    }

    @Test
    @DisplayName("addBusinessDays: 2028 추석 연휴(개천절 중첩)를 통째로 건너뛴다 (연휴 직전 결제 → 첫 영업일)")
    void addBusinessDays_skipsChuseok2028Run() {
        // 2028-09-29(금) + 1 영업일: 토·일·추석연휴(10/2~4)·중첩대체(10/5 목) 모두 스킵 → 10-06(금)
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2028, 9, 29), 1))
                .isEqualTo(LocalDate.of(2028, 10, 6));
    }

    @Test
    @DisplayName("addBusinessDays: 2029 추석 연휴+대체(2일)를 통째로 건너뛴다 — 토·일 이틀 중첩")
    void addBusinessDays_skipsChuseok2029Run() {
        // 2029-09-20(목) + 1 영업일: 21 금(추석전날)·22 토·23 일·24 월(대체)·25 화(대체) 스킵 → 09-26(수)
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2029, 9, 20), 1))
                .isEqualTo(LocalDate.of(2029, 9, 26));
    }

    @Test
    @DisplayName("addBusinessDays: 2030 설날 연휴+대체(2일)를 통째로 건너뛴다 — 토·일 이틀 중첩")
    void addBusinessDays_skipsSeollal2030Run() {
        // 2030-02-01(금) + 1 영업일: 2 토·3 일(설날)·4 월·5 화(대체)·6 수(대체) 스킵 → 02-07(목)
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2030, 2, 1), 1))
                .isEqualTo(LocalDate.of(2030, 2, 7));
    }

    @Test
    @DisplayName("isBusinessDay: 미등재 연도(2031)의 음력 명절은 영업일로 간주된다 (문서화된 한계)")
    void isBusinessDay_unregisteredYearLunarHolidayTreatedAsBusinessDay() {
        // 2031 설날(2031-01-23, 목)은 상수 미등재 → 주말·양력 고정만 판정하므로 영업일(true). 한계 고정.
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2031, 1, 23))).isTrue();
        // 반면 양력 고정 공휴일은 연도 무관 항상 비영업일 — 2031 신정.
        assertThat(BusinessDayCalculator.isBusinessDay(LocalDate.of(2031, 1, 1))).isFalse();
    }

    // ── 추가 공휴일 주입(임시공휴일) — 생성자/정적 팩토리 주입 구조 ────────────────────

    @Test
    @DisplayName("주입: 추가 공휴일(임시공휴일)을 얹으면 그날이 비영업일이 되고 정산일이 밀린다")
    void withExtraHolidays_injectedHolidayShiftsSettlementDate() {
        // 2026-04-29(수)는 표준 캘린더에선 영업일. 임시공휴일로 주입하면 비영업일이 된다.
        LocalDate tempHoliday = LocalDate.of(2026, 4, 29);
        BusinessDayCalculator injected = BusinessDayCalculator.withExtraHolidays(Set.of(tempHoliday));

        assertThat(injected.isBusinessDayOn(tempHoliday)).as("주입된 임시공휴일은 비영업일").isFalse();
        // 2026-04-28(화) + 1 영업일: 표준이면 04-29(수)이나, 04-29 가 공휴일이라 04-30(목)으로 밀린다.
        assertThat(injected.addBusinessDaysFrom(LocalDate.of(2026, 4, 28), 1))
                .as("임시공휴일 주입으로 정산일이 하루 밀린다").isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    @DisplayName("무회귀: 표준 캘린더(추가 공휴일 없음)는 주입 전 하드코딩 동작과 동일")
    void standard_noExtraHolidays_matchesLegacyDefault() {
        BusinessDayCalculator standard = BusinessDayCalculator.standard();
        // 04-29(수)는 표준 캘린더에선 여전히 영업일 — 주입 캘린더와 격리(누수 없음).
        assertThat(standard.isBusinessDayOn(LocalDate.of(2026, 4, 29))).isTrue();
        assertThat(standard.addBusinessDaysFrom(LocalDate.of(2026, 4, 28), 1))
                .isEqualTo(LocalDate.of(2026, 4, 29));
        // 정적 진입점(설치 전 기본=표준)도 동일.
        assertThat(BusinessDayCalculator.addBusinessDays(LocalDate.of(2026, 4, 28), 1))
                .isEqualTo(LocalDate.of(2026, 4, 29));
    }

    @Test
    @DisplayName("주입: null/빈 세트는 표준 싱글턴을 재사용 (불필요한 인스턴스 회피)")
    void withExtraHolidays_nullOrEmpty_returnsStandardSingleton() {
        assertThat(BusinessDayCalculator.withExtraHolidays(null)).isSameAs(BusinessDayCalculator.standard());
        assertThat(BusinessDayCalculator.withExtraHolidays(Set.of())).isSameAs(BusinessDayCalculator.standard());
        assertThat(new BusinessDayCalculator(null).extraHolidays()).isEmpty();
    }

    @Test
    @DisplayName("설치: 기본 캘린더로 설치하면 정적 도메인 호출부(SettlementCycle)가 임시공휴일을 반영한다")
    void installDefault_makesStaticDomainPathHonorExtraHolidays() {
        LocalDate tempHoliday = LocalDate.of(2026, 4, 29);
        // 설치 전: 표준 — 04-28 화 + T+1 → 04-29 수
        assertThat(SettlementCycle.T_PLUS_1.resolveSettlementDate(LocalDate.of(2026, 4, 28)))
                .isEqualTo(LocalDate.of(2026, 4, 29));

        BusinessDayCalculator.installDefault(BusinessDayCalculator.withExtraHolidays(Set.of(tempHoliday)));

        // 설치 후: 정적 호출부가 임시공휴일을 반영 → 04-30 목으로 밀린다(@AfterEach 로 표준 복원).
        assertThat(BusinessDayCalculator.isBusinessDay(tempHoliday)).isFalse();
        assertThat(SettlementCycle.T_PLUS_1.resolveSettlementDate(LocalDate.of(2026, 4, 28)))
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }
}
