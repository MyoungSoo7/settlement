package github.lms.lemuel.company.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyWorkforceTest {

    private CompanyWorkforce valid(int headcount, String monthlyBilledAmount) {
        return new CompanyWorkforce("주식회사에고이즘", "866759", "전자상거래 소매업",
                "서울특별시 성동구 연무장19길", YearMonth.of(2026, 6), headcount, new BigDecimal(monthlyBilledAmount));
    }

    @Test
    @DisplayName("사업장명 누락은 거부한다")
    void rejectsBlankWorkplaceName() {
        assertThrows(IllegalArgumentException.class, () ->
                new CompanyWorkforce(" ", "866759", "전자상거래 소매업", "주소",
                        YearMonth.of(2026, 6), 50, new BigDecimal("1000000")));
    }

    @Test
    @DisplayName("가입자수는 음수를 거부한다")
    void rejectsNegativeHeadcount() {
        assertThrows(IllegalArgumentException.class, () -> valid(-1, "1000000"));
    }

    @Test
    @DisplayName("당월고지금액은 음수를 거부한다")
    void rejectsNegativeMonthlyBilledAmount() {
        assertThrows(IllegalArgumentException.class, () -> valid(4, "-1"));
    }

    @Test
    @DisplayName("추정연봉 = (당월고지금액×12) / (가입자수×9%) — 국민연금 대납 보험료 역산")
    void estimatesAnnualSalaryFromPensionContribution() {
        CompanyWorkforce workforce = valid(4, "943140");
        Optional<BigDecimal> estimated = workforce.estimatedAnnualSalary();
        assertTrue(estimated.isPresent());
        assertEquals(0, new BigDecimal("31438000").compareTo(estimated.get()));
    }

    @Test
    @DisplayName("가입자수 0이면 추정연봉을 계산하지 않는다")
    void noEstimateWhenHeadcountZero() {
        CompanyWorkforce workforce = valid(0, "0");
        assertEquals(Optional.empty(), workforce.estimatedAnnualSalary());
    }
}
