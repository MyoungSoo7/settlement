package github.lms.lemuel.company.domain;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/** 국민연금 보험료율 고시 적용 구간. */
public final class NpsContributionRate {

    private record Bracket(YearMonth from, YearMonth toInclusive, BigDecimal rate) {

        private boolean covers(YearMonth month) {
            return !month.isBefore(from) && !month.isAfter(toInclusive);
        }
    }

    private static final List<Bracket> BRACKETS = List.of(
            new Bracket(YearMonth.of(2025, 1), YearMonth.of(2025, 12), new BigDecimal("0.09")),
            new Bracket(YearMonth.of(2026, 1), YearMonth.of(2026, 12), new BigDecimal("0.095")));

    private NpsContributionRate() {
    }

    public static Optional<BigDecimal> rateOf(YearMonth snapshotMonth) {
        if (snapshotMonth == null) {
            return Optional.empty();
        }
        return BRACKETS.stream()
                .filter(bracket -> bracket.covers(snapshotMonth))
                .map(Bracket::rate)
                .findFirst();
    }
}
