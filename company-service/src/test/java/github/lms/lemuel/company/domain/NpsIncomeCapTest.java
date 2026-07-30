package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpsIncomeCapTest {

    @Test
    @DisplayName("기준소득월액 상한액은 7월 시작 고시 구간으로 해석한다")
    void resolvesCapByNoticeBracket() {
        assertEquals(0, new BigDecimal("5530000").compareTo(cap(2022, 7)));
        assertEquals(0, new BigDecimal("5530000").compareTo(cap(2023, 6)));
        assertEquals(0, new BigDecimal("5900000").compareTo(cap(2023, 7)));
        assertEquals(0, new BigDecimal("6170000").compareTo(cap(2024, 7)));
        assertEquals(0, new BigDecimal("6370000").compareTo(cap(2025, 7)));
        // 고시 구간은 7월 시작 — 2026-06 은 아직 직전(2025-07~) 구간이다.
        assertEquals(0, new BigDecimal("6370000").compareTo(cap(2026, 6)));
        assertEquals(0, new BigDecimal("6590000").compareTo(cap(2026, 7)));
        assertEquals(0, new BigDecimal("6590000").compareTo(cap(2027, 6)));
    }

    @Test
    @DisplayName("고시표 범위 밖 월은 상한을 단정하지 않는다 — 조회를 거부하지 않고 빈 값")
    void unknownOutsideNoticeTable() {
        assertEquals(Optional.empty(), NpsIncomeCap.monthlyCapOf(YearMonth.of(2022, 6)));
        assertEquals(Optional.empty(), NpsIncomeCap.monthlyCapOf(YearMonth.of(2027, 7)));
        assertEquals(Optional.empty(), NpsIncomeCap.monthlyCapOf(null));
    }

    @Test
    @DisplayName("연 상한 = 월 상한 × 12")
    void annualCapIsTwelveTimesMonthly() {
        Optional<BigDecimal> annual = NpsIncomeCap.annualCapOf(YearMonth.of(2026, 6));

        assertTrue(annual.isPresent());
        assertEquals(0, new BigDecimal("76440000").compareTo(annual.get()));
        assertEquals(Optional.empty(), NpsIncomeCap.annualCapOf(YearMonth.of(2021, 1)));
    }

    private BigDecimal cap(int year, int month) {
        return NpsIncomeCap.monthlyCapOf(YearMonth.of(year, month)).orElseThrow();
    }
}
