package github.lms.lemuel.settlement.domain;

import github.lms.lemuel.settlement.domain.exception.SettlementInvariantViolationException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 한국 영업일 계산 헬퍼.
 *
 * <p>{@code T+N} 정산 (예: T+1, T+3, T+7) 에서 N 영업일을 더한 정산일 계산. 토·일은 비영업일이며,
 * 공휴일은 세 축으로 판정한다:
 * <ul>
 *   <li>{@link #KOREAN_FIXED_HOLIDAYS} — 매년 같은 날짜인 <b>양력 고정</b> 공휴일(MM-DD).</li>
 *   <li>{@link #LUNAR_AND_SUBSTITUTE_HOLIDAYS} — 음력 기반 명절(설·추석·부처님오신날)과
 *       <b>대체공휴일</b>은 해가 바뀌면 날짜가 달라지므로 연도별 전체 날짜(YYYY-MM-DD)를 하드코딩한다.</li>
 *   <li>{@link #extraHolidays} — <b>인스턴스별로 주입되는 추가 공휴일</b>(정부 지정 임시공휴일 등).
 *       위 두 상수는 코드에 하드코딩돼 있어 새 임시공휴일이 확정되면 배포가 필요했으나, 이 축은
 *       설정({@code app.settlement.extra-holidays})으로 주입해 <b>코드 변경 없이</b> 반영한다.</li>
 * </ul>
 *
 * <p><b>주입 구조</b>: 이 클래스는 추가 공휴일 세트를 <b>생성자로 주입</b>받는 인스턴스로 구성한다.
 * 추가 공휴일이 없는 표준 캘린더는 {@link #standard()} 싱글턴이며, {@link #addBusinessDays(LocalDate, int)}
 * 등 정적 메서드는 {@linkplain #installDefault(BusinessDayCalculator) 설치된 기본 인스턴스}에 위임한다.
 * 도메인 정적 호출부({@link SettlementCycle}·{@link HoldbackPolicy})는 그대로 두고, config 계층이 기동
 * 시점에 임시공휴일을 얹은 인스턴스를 기본으로 설치하면 정산일 계산이 이를 반영한다 — 도메인은
 * Spring/JPA 의존성 0(주입 배선은 config 계층 몫)을 유지한다.
 *
 * <p><b>한계(중요)</b>: {@link #LUNAR_AND_SUBSTITUTE_HOLIDAYS} 는 명시적으로 등재된 연도
 * (<b>{@value #FIRST_REGISTERED_YEAR}~{@value #LAST_REGISTERED_YEAR}</b>)만 정확하다. 이 범위 밖의 연도는
 * <b>주말 + 양력 고정 공휴일(+ 주입된 추가 공휴일)</b>만 비영업일로 처리하며, 그 해의 음력 명절·대체공휴일은
 * 영업일로 잘못 간주된다. 새해 캘린더 확정 시 해당 연도 상수를 추가하거나, 확정 즉시 {@link #extraHolidays}
 * 로 주입해 보정할 수 있다. 궁극적으로는 외부(정부24 OpenAPI / 운영팀 캘린더 테이블) 주입으로 대체 가능.
 *
 * <p>등재 연도의 음력→양력 변환은 천문학적으로 확정된 값이며, 대체공휴일은 「관공서의 공휴일에 관한
 * 규정」제3조를 그대로 적용해 산출했다: ① 설·추석 연휴가 토·일 또는 다른 공휴일과 겹치면 겹친 일수만큼
 * 다음 비공휴일 평일로, ② 어린이날은 토·일·타 공휴일 중첩 시, ③ 삼일절·부처님오신날·광복절·개천절·한글날·
 * 성탄절은 토·일 중첩 시 각각 대체공휴일을 부여한다(신정·현충일은 대체 대상 아님). 2028년 추석(10-03)이
 * 개천절과 같은 날이라 대체공휴일(10-05)이 붙는 중첩 사례까지 반영돼 있다.
 *
 * <p>설계상 도메인 순수 — Spring/JPA 의존성 없음.
 */
public final class BusinessDayCalculator {

    /** 매년 고정 공휴일 (월-일). 음력 기준 명절·대체공휴일은 {@link #LUNAR_AND_SUBSTITUTE_HOLIDAYS} 로 별도 처리. */
    private static final Set<String> KOREAN_FIXED_HOLIDAYS = Set.of(
            "01-01", // 신정
            "03-01", // 삼일절
            "05-05", // 어린이날
            "06-06", // 현충일
            "08-15", // 광복절
            "10-03", // 개천절
            "10-09", // 한글날
            "12-25"  // 성탄절
    );

    /** 음력 명절·대체공휴일이 정확히 등재된 첫/마지막 연도(경계 밖은 주말+양력 고정 공휴일만 판정). */
    private static final int FIRST_REGISTERED_YEAR = 2026;
    private static final int LAST_REGISTERED_YEAR = 2030;

    /**
     * 2026년 음력 명절 + 대체공휴일 — 공식 관보 기준.
     * <ul>
     *   <li>설날 연휴: 02-16(월)·02-17(설날, 화)·02-18(수)</li>
     *   <li>삼일절 대체: 03-02(월) — 03-01 일요일</li>
     *   <li>부처님오신날 05-24(일) + 대체 05-25(월)</li>
     *   <li>광복절 대체: 08-17(월) — 08-15 토요일</li>
     *   <li>추석 연휴: 09-24(목)·09-25(추석, 금)·09-26(토) + 대체 09-28(월)</li>
     *   <li>개천절 대체: 10-05(월) — 10-03 토요일</li>
     * </ul>
     */
    private static final Set<LocalDate> HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18), // 설날 연휴
            LocalDate.of(2026, 3, 2),                                                        // 삼일절 대체
            LocalDate.of(2026, 5, 24), LocalDate.of(2026, 5, 25),                            // 부처님오신날 + 대체
            LocalDate.of(2026, 8, 17),                                                       // 광복절 대체
            LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26), // 추석 연휴
            LocalDate.of(2026, 9, 28),                                                       // 추석 대체
            LocalDate.of(2026, 10, 5));                                                      // 개천절 대체

    /**
     * 2027년.
     * <ul>
     *   <li>설날 연휴: 02-06(토)·02-07(설날, 일)·02-08(월) + 대체 02-09(화)·02-10(수) — 토·일 이틀 중첩</li>
     *   <li>부처님오신날 05-13(목) — 평일, 대체 없음</li>
     *   <li>광복절 대체: 08-16(월) — 08-15 일요일</li>
     *   <li>추석 연휴: 09-14(화)·09-15(추석, 수)·09-16(목) — 평일, 대체 없음</li>
     *   <li>개천절 대체: 10-04(월) — 10-03 일요일 / 한글날 대체: 10-11(월) — 10-09 토요일</li>
     *   <li>성탄절 대체: 12-27(월) — 12-25 토요일</li>
     * </ul>
     */
    private static final Set<LocalDate> HOLIDAYS_2027 = Set.of(
            LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 7), LocalDate.of(2027, 2, 8),    // 설날 연휴
            LocalDate.of(2027, 2, 9), LocalDate.of(2027, 2, 10),                             // 설날 대체(2일)
            LocalDate.of(2027, 5, 13),                                                       // 부처님오신날
            LocalDate.of(2027, 8, 16),                                                       // 광복절 대체
            LocalDate.of(2027, 9, 14), LocalDate.of(2027, 9, 15), LocalDate.of(2027, 9, 16), // 추석 연휴
            LocalDate.of(2027, 10, 4),                                                       // 개천절 대체
            LocalDate.of(2027, 10, 11),                                                      // 한글날 대체
            LocalDate.of(2027, 12, 27));                                                     // 성탄절 대체

    /**
     * 2028년.
     * <ul>
     *   <li>설날 연휴: 01-26(수)·01-27(설날, 목)·01-28(금) — 평일, 대체 없음</li>
     *   <li>부처님오신날 05-02(화) — 평일, 대체 없음</li>
     *   <li>추석 연휴: 10-02(월)·10-03(추석, 화)·10-04(수) + 대체 10-05(목) — 추석(10-03)이 개천절과 같은 날이라 중첩 대체</li>
     * </ul>
     */
    private static final Set<LocalDate> HOLIDAYS_2028 = Set.of(
            LocalDate.of(2028, 1, 26), LocalDate.of(2028, 1, 27), LocalDate.of(2028, 1, 28), // 설날 연휴
            LocalDate.of(2028, 5, 2),                                                        // 부처님오신날
            LocalDate.of(2028, 10, 2), LocalDate.of(2028, 10, 3), LocalDate.of(2028, 10, 4), // 추석 연휴(10-03=개천절)
            LocalDate.of(2028, 10, 5));                                                      // 추석·개천절 중첩 대체

    /**
     * 2029년.
     * <ul>
     *   <li>설날 연휴: 02-12(월)·02-13(설날, 화)·02-14(수) — 평일, 대체 없음</li>
     *   <li>어린이날 대체: 05-07(월) — 05-05 토요일</li>
     *   <li>부처님오신날 05-20(일) + 대체 05-21(월)</li>
     *   <li>추석 연휴: 09-21(금)·09-22(추석, 토)·09-23(일) + 대체 09-24(월)·09-25(화) — 토·일 이틀 중첩</li>
     * </ul>
     */
    private static final Set<LocalDate> HOLIDAYS_2029 = Set.of(
            LocalDate.of(2029, 2, 12), LocalDate.of(2029, 2, 13), LocalDate.of(2029, 2, 14), // 설날 연휴
            LocalDate.of(2029, 5, 7),                                                        // 어린이날 대체
            LocalDate.of(2029, 5, 20), LocalDate.of(2029, 5, 21),                            // 부처님오신날 + 대체
            LocalDate.of(2029, 9, 21), LocalDate.of(2029, 9, 22), LocalDate.of(2029, 9, 23), // 추석 연휴
            LocalDate.of(2029, 9, 24), LocalDate.of(2029, 9, 25));                           // 추석 대체(2일)

    /**
     * 2030년.
     * <ul>
     *   <li>설날 연휴: 02-02(토)·02-03(설날, 일)·02-04(월) + 대체 02-05(화)·02-06(수) — 토·일 이틀 중첩</li>
     *   <li>어린이날 대체: 05-06(월) — 05-05 일요일</li>
     *   <li>부처님오신날 05-09(목) — 평일, 대체 없음</li>
     *   <li>추석 연휴: 09-11(수)·09-12(추석, 목)·09-13(금) — 평일, 대체 없음</li>
     * </ul>
     */
    private static final Set<LocalDate> HOLIDAYS_2030 = Set.of(
            LocalDate.of(2030, 2, 2), LocalDate.of(2030, 2, 3), LocalDate.of(2030, 2, 4),    // 설날 연휴
            LocalDate.of(2030, 2, 5), LocalDate.of(2030, 2, 6),                              // 설날 대체(2일)
            LocalDate.of(2030, 5, 6),                                                        // 어린이날 대체
            LocalDate.of(2030, 5, 9),                                                        // 부처님오신날
            LocalDate.of(2030, 9, 11), LocalDate.of(2030, 9, 12), LocalDate.of(2030, 9, 13)); // 추석 연휴

    /**
     * 연도별 음력 명절 + 대체공휴일 (전체 날짜)의 합집합. 양력 고정 공휴일은 여기 넣지 않는다
     * ({@link #KOREAN_FIXED_HOLIDAYS} 담당). 등재 연도 범위는
     * {@value #FIRST_REGISTERED_YEAR}~{@value #LAST_REGISTERED_YEAR} — 새 연도 상수를 추가하면 여기 union 에도 더한다.
     */
    private static final Set<LocalDate> LUNAR_AND_SUBSTITUTE_HOLIDAYS =
            Stream.of(HOLIDAYS_2026, HOLIDAYS_2027, HOLIDAYS_2028, HOLIDAYS_2029, HOLIDAYS_2030)
                    .flatMap(Set::stream)
                    .collect(Collectors.toUnmodifiableSet());

    /** 추가 공휴일 없는 표준 캘린더(주말 + 양력 고정 + {@value #FIRST_REGISTERED_YEAR}~{@value #LAST_REGISTERED_YEAR} 음력·대체). */
    private static final BusinessDayCalculator STANDARD = new BusinessDayCalculator(Set.of());

    /**
     * 정적 편의 메서드({@link #isBusinessDay}·{@link #addBusinessDays})가 위임하는 프로세스 기본 인스턴스.
     * 기본은 {@link #STANDARD} 이며, config 계층이 기동 시점에 {@link #installDefault} 로 임시공휴일을 얹은
     * 인스턴스를 1회 설치할 수 있다. 설치 이후 도메인 정적 호출부가 그 캘린더를 반영한다. 기동 시 1회 설정,
     * 이후 읽기 전용이라 {@code volatile} 로 가시성만 보장한다.
     */
    private static volatile BusinessDayCalculator defaultInstance = STANDARD;

    /** 하드코딩 상수 위에 얹히는 인스턴스별 추가 공휴일(정부 지정 임시공휴일 등). 불변. */
    private final Set<LocalDate> extraHolidays;

    /**
     * 추가 공휴일 세트를 주입해 캘린더를 구성한다. {@code null} 은 빈 세트로 정규화하며, 방어 복사로 불변을 보장한다.
     *
     * @param extraHolidays 하드코딩 상수에 더해 비영업일로 취급할 추가 공휴일(예: 임시공휴일). null/빈 세트 허용.
     */
    public BusinessDayCalculator(Collection<LocalDate> extraHolidays) {
        this.extraHolidays = (extraHolidays == null || extraHolidays.isEmpty())
                ? Set.of()
                : Set.copyOf(extraHolidays);
    }

    /** 추가 공휴일이 없는 표준 캘린더 싱글턴. */
    public static BusinessDayCalculator standard() {
        return STANDARD;
    }

    /**
     * 추가 공휴일을 주입한 캘린더. 추가분이 없으면 {@link #STANDARD} 싱글턴을 재사용한다(불필요한 인스턴스 회피).
     */
    public static BusinessDayCalculator withExtraHolidays(Collection<LocalDate> extraHolidays) {
        if (extraHolidays == null || extraHolidays.isEmpty()) {
            return STANDARD;
        }
        return new BusinessDayCalculator(extraHolidays);
    }

    /**
     * 정적 도메인 호출부({@link SettlementCycle}·{@link HoldbackPolicy})가 참조하는 기본 캘린더를 교체한다.
     * config 계층이 기동 시점에 1회 호출하는 것을 전제로 한다(런타임 도중 변경 대상 아님).
     */
    public static void installDefault(BusinessDayCalculator calculator) {
        defaultInstance = Objects.requireNonNull(calculator, "calculator 필수");
    }

    /** 현재 설치된 기본 캘린더(정적 메서드가 위임하는 인스턴스). */
    public static BusinessDayCalculator activeDefault() {
        return defaultInstance;
    }

    /** 이 캘린더에 주입된 추가 공휴일(불변 뷰). */
    public Set<LocalDate> extraHolidays() {
        return extraHolidays;
    }

    /**
     * 이 캘린더 기준, 주어진 날짜로부터 N 영업일 후. N=0 이면 시작일이 영업일이면 그대로, 비영업일이면 다음 영업일.
     *
     * @param from 시작일 (보통 결제일)
     * @param n    더할 영업일 수 (0 이상)
     */
    public LocalDate addBusinessDaysFrom(LocalDate from, int n) {
        if (from == null) throw new SettlementInvariantViolationException("from 필수");
        if (n < 0) throw new SettlementInvariantViolationException("n 은 0 이상");

        LocalDate cursor = from;
        // n=0 인 경우: 시작일이 영업일이 아니면 다음 영업일까지 전진
        if (n == 0) {
            while (!isBusinessDayOn(cursor)) {
                cursor = cursor.plusDays(1);
            }
            return cursor;
        }

        int added = 0;
        while (added < n) {
            cursor = cursor.plusDays(1);
            if (isBusinessDayOn(cursor)) added++;
        }
        return cursor;
    }

    /**
     * 이 캘린더 기준, 영업일인지 (월~금 + 공휴일 아님). 공휴일은 양력 고정({@link #KOREAN_FIXED_HOLIDAYS}),
     * 연도별 음력·대체({@link #LUNAR_AND_SUBSTITUTE_HOLIDAYS}), 인스턴스 주입 추가 공휴일({@link #extraHolidays})
     * 세 축으로 판정한다. 음력·대체에 미등재된 연도의 명절은 영업일로 간주된다(클래스 Javadoc 한계 참조).
     */
    public boolean isBusinessDayOn(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        String mmdd = String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
        if (KOREAN_FIXED_HOLIDAYS.contains(mmdd)) return false;
        if (LUNAR_AND_SUBSTITUTE_HOLIDAYS.contains(date)) return false;
        return !extraHolidays.contains(date);
    }

    /**
     * 설치된 기본 캘린더 기준 N 영업일 후 — 도메인 정적 호출부 하위호환 진입점. {@link #installDefault} 로
     * 임시공휴일이 얹힌 인스턴스가 설치돼 있으면 그 캘린더를 반영한다.
     */
    public static LocalDate addBusinessDays(LocalDate from, int n) {
        return defaultInstance.addBusinessDaysFrom(from, n);
    }

    /** 설치된 기본 캘린더 기준 영업일 판정 — 정적 하위호환 진입점. */
    public static boolean isBusinessDay(LocalDate date) {
        return defaultInstance.isBusinessDayOn(date);
    }
}
