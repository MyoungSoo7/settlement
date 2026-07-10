package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyFinancialsTest {

    private static AnnualStatement year(int y) {
        return new AnnualStatement(y, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void hasStatements() {
        assertThat(new CompanyFinancials("005930", "x", "KOSPI", List.of()).hasStatements()).isFalse();
        assertThat(new CompanyFinancials("005930", "x", "KOSPI", null).hasStatements()).isFalse();
        assertThat(new CompanyFinancials("005930", "x", "KOSPI", List.of(year(2024))).hasStatements()).isTrue();
    }

    @Test
    void latest와_previous는_연도_내림차순으로_고른다() {
        CompanyFinancials cf = new CompanyFinancials("005930", "x", "KOSPI",
                List.of(year(2022), year(2024), year(2023)));
        assertThat(cf.latest().fiscalYear()).isEqualTo(2024);
        assertThat(cf.previous().fiscalYear()).isEqualTo(2023);
    }

    @Test
    void 단일연도면_previous는_null() {
        CompanyFinancials cf = new CompanyFinancials("005930", "x", "KOSPI", List.of(year(2024)));
        assertThat(cf.latest().fiscalYear()).isEqualTo(2024);
        assertThat(cf.previous()).isNull();
    }
}
