package github.lms.lemuel.settlement.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * 한국 영업일 계산 헬퍼.
 *
 * <p>{@code T+N} 정산 (예: T+1, T+3, T+7) 에서 N 영업일을 더한 정산일 계산. 토·일은 비영업일이며,
 * 공휴일은 외부 캘린더 서비스 연동 전 단계에서는 추가 옵션 인자로 받는다.
 *
 * <p>간단한 한국 고정 공휴일은 {@link #KOREAN_FIXED_HOLIDAYS} 에 미리 들어있으나, 실 운영에서는
 * 공휴일 정보를 외부 (정부24 OpenAPI / 운영팀 입력 캘린더 테이블) 에서 주입해야 정확하다.
 *
 * <p>설계상 도메인 순수 — Spring/JPA 의존성 없음.
 */
public final class BusinessDayCalculator {

    /** 매년 고정 공휴일 (월-일). 음력 기준 명절은 별도 처리 필요. */
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

    private BusinessDayCalculator() { }

    /**
     * 주어진 날짜로부터 N 영업일 후. N=0 이면 시작일이 영업일이면 그대로, 비영업일이면 다음 영업일.
     *
     * @param from 시작일 (보통 결제일)
     * @param n    더할 영업일 수 (0 이상)
     */
    public static LocalDate addBusinessDays(LocalDate from, int n) {
        if (from == null) throw new IllegalArgumentException("from 필수");
        if (n < 0) throw new IllegalArgumentException("n 은 0 이상");

        LocalDate cursor = from;
        // n=0 인 경우: 시작일이 영업일이 아니면 다음 영업일까지 전진
        if (n == 0) {
            while (!isBusinessDay(cursor)) {
                cursor = cursor.plusDays(1);
            }
            return cursor;
        }

        int added = 0;
        while (added < n) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor)) added++;
        }
        return cursor;
    }

    /**
     * 영업일인지 (월~금 + 공휴일 아님).
     */
    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        String mmdd = String.format("%02d-%02d", date.getMonthValue(), date.getDayOfMonth());
        return !KOREAN_FIXED_HOLIDAYS.contains(mmdd);
    }
}
