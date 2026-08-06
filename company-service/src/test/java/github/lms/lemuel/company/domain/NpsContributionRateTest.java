package github.lms.lemuel.company.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpsContributionRateTest {

    @ParameterizedTest
    @CsvSource({
            "2025-12, 0.09",
            "2026-01, 0.095",
            "2026-06, 0.095",
            "2026-12, 0.095"
    })
    void resolvesContributionRateAtSupportedPeriodBoundaries(String month, String expectedRate) {
        assertEquals(0, new BigDecimal(expectedRate)
                .compareTo(NpsContributionRate.rateOf(YearMonth.parse(month)).orElseThrow()));
    }

    @ParameterizedTest
    @CsvSource(value = {"2027-01", "null"}, nullValues = "null")
    void returnsEmptyOutsideSupportedPeriod(String month) {
        YearMonth snapshotMonth = month == null ? null : YearMonth.parse(month);

        assertEquals(java.util.Optional.empty(), NpsContributionRate.rateOf(snapshotMonth));
    }
}
