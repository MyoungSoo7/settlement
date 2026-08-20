package github.lms.lemuel.report.domain;

import github.lms.lemuel.report.domain.exception.InvalidReportPeriodException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 통계 조회 기간 — 화면이 넘긴 두 날짜가 집계 가능한 구간인지 도메인이 판정한다.
 *
 * <p><b>상한을 두는 이유</b>: 기간에 제한이 없으면 연도 오타 한 번(2026 → 2016)이 전 기간 스캔이
 * 되어 운영 DB 를 물고 늘어진다. 366일은 "윤년 1년"이라 연간 리포트가 경계에 딱 맞는다.
 *
 * <p><b>직전 기간을 도메인이 만드는 이유</b>: "전기 대비"의 분모를 화면이 정하면 화면마다 달라진다.
 * 여기서는 <b>같은 길이를 바로 앞에 붙인다</b> — 1월(31일)의 직전은 12월(31일)이지, "지난달"이
 * 아니다. 달 길이가 들쭉날쭉해 비교가 흔들리는 것보다 길이를 고정하는 편이 읽기 쉽다.
 */
public record ReportPeriod(LocalDate from, LocalDate to) {

    /** 조회 가능한 최대 일수(양 끝 포함) — 윤년 1년. */
    public static final long MAX_DAYS = 366;

    public static ReportPeriod of(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidReportPeriodException(
                    "조회 기간의 시작일과 종료일이 모두 필요합니다: from=" + from + ", to=" + to);
        }
        if (from.isAfter(to)) {
            throw new InvalidReportPeriodException(
                    "조회 기간의 시작일이 종료일보다 뒤입니다: from=" + from + ", to=" + to);
        }
        long days = daysBetween(from, to);
        if (days > MAX_DAYS) {
            throw new InvalidReportPeriodException(
                    "조회 기간은 최대 " + MAX_DAYS + "일입니다: " + days + "일 요청됨");
        }
        // 검증을 통과한 값만 정본 생성자로 들어간다. previous() 는 이 생성자를 직접 쓰므로
        // 상한 검증이 내부 파생(직전 기간 계산)까지 막지 않는다.
        return new ReportPeriod(from, to);
    }

    /** 양 끝을 포함한 일수. */
    public long days() {
        return daysBetween(from, to);
    }

    /** SQL 반개구간의 열린 끝 — {@code settlement_date < ?} 로 쓰라고 종료 다음 날을 준다. */
    public LocalDate endExclusive() {
        return to.plusDays(1);
    }

    /** 같은 길이를 바로 앞에 붙인 기간. 전기 대비 비교의 분모다. */
    public ReportPeriod previous() {
        LocalDate previousTo = from.minusDays(1);
        return new ReportPeriod(previousTo.minusDays(days() - 1), previousTo);
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}
