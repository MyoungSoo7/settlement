package github.lms.lemuel.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 영업일 캘린더 설정({@code app.settlement.*}).
 *
 * <p>{@code extra-holidays} 는 하드코딩된 공휴일 상수({@code BusinessDayCalculator} 의 양력 고정 +
 * 연도별 음력·대체) 위에 얹히는 <b>추가 공휴일</b>이다. 정부가 임시공휴일(예: 국가 애도의 날, 선거일)을
 * 지정하면 코드·배포 없이 이 목록(각 {@code yyyy-MM-dd})만 채워 정산일·홀드백 해제일 계산에 반영한다.
 * 기본값은 빈 목록 — 미설정 시 현행 하드코딩 캘린더와 동일하게 동작한다.
 *
 * <p>바인딩은 config 계층에서만 일어나며 도메인({@code BusinessDayCalculator})은 프레임워크 의존 0 을 유지한다.
 */
@ConfigurationProperties(prefix = "app.settlement")
public record SettlementCalendarProperties(List<LocalDate> extraHolidays) {

    public SettlementCalendarProperties {
        extraHolidays = extraHolidays == null ? List.of() : List.copyOf(extraHolidays);
    }
}
