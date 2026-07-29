package github.lms.lemuel.company.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 국민연금 기준소득월액 상한액 고시표. 상한 때문에 고소득 사업장의 당월고지금액이 같은 값에 몰려
 * 추정연봉·백분위가 무의미해지므로, 상한 도달 여부를 신뢰도 플래그로 알리는 데 쓴다.
 *
 * <p>고시액은 공표된 고정값이라 코드에 내장한다(외부 조회 없음). 적용 구간은 매년 7월 시작이다.
 * 표 범위 밖 기준월은 상한을 <b>단정하지 않는다</b> — 조회를 거부하는 대신 빈 값으로 두어
 * "판정 근거 없음"을 그대로 노출한다(없는 상한을 추정해 잘못된 플래그를 켜지 않는다).
 */
public final class NpsIncomeCap {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private record Bracket(YearMonth from, YearMonth toInclusive, BigDecimal monthlyCap) {

        private boolean covers(YearMonth month) {
            return !month.isBefore(from) && !month.isAfter(toInclusive);
        }
    }

    private static final List<Bracket> BRACKETS = List.of(
            new Bracket(YearMonth.of(2022, 7), YearMonth.of(2023, 6), new BigDecimal("5530000")),
            new Bracket(YearMonth.of(2023, 7), YearMonth.of(2024, 6), new BigDecimal("5900000")),
            new Bracket(YearMonth.of(2024, 7), YearMonth.of(2025, 6), new BigDecimal("6170000")),
            new Bracket(YearMonth.of(2025, 7), YearMonth.of(2026, 6), new BigDecimal("6370000")),
            new Bracket(YearMonth.of(2026, 7), YearMonth.of(2027, 6), new BigDecimal("6590000")));

    private NpsIncomeCap() {
    }

    public static Optional<BigDecimal> monthlyCapOf(YearMonth snapshotMonth) {
        if (snapshotMonth == null) {
            return Optional.empty();
        }
        return BRACKETS.stream()
                .filter(bracket -> bracket.covers(snapshotMonth))
                .map(Bracket::monthlyCap)
                .findFirst();
    }

    public static Optional<BigDecimal> annualCapOf(YearMonth snapshotMonth) {
        return monthlyCapOf(snapshotMonth).map(cap -> cap.multiply(MONTHS_PER_YEAR));
    }
}
