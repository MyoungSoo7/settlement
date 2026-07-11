package github.lms.lemuel.investment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentGradeTest {

    @Test
    void 등급_경계값을_결정적으로_매핑한다() {
        assertThat(InvestmentGrade.fromScore(100)).isEqualTo(InvestmentGrade.AAA);
        assertThat(InvestmentGrade.fromScore(90)).isEqualTo(InvestmentGrade.AAA);
        assertThat(InvestmentGrade.fromScore(89)).isEqualTo(InvestmentGrade.AA);
        assertThat(InvestmentGrade.fromScore(80)).isEqualTo(InvestmentGrade.AA);
        assertThat(InvestmentGrade.fromScore(79)).isEqualTo(InvestmentGrade.A);
        assertThat(InvestmentGrade.fromScore(70)).isEqualTo(InvestmentGrade.A);
        assertThat(InvestmentGrade.fromScore(69)).isEqualTo(InvestmentGrade.BBB);
        assertThat(InvestmentGrade.fromScore(60)).isEqualTo(InvestmentGrade.BBB);
        assertThat(InvestmentGrade.fromScore(59)).isEqualTo(InvestmentGrade.BB);
        assertThat(InvestmentGrade.fromScore(50)).isEqualTo(InvestmentGrade.BB);
        assertThat(InvestmentGrade.fromScore(49)).isEqualTo(InvestmentGrade.B);
        assertThat(InvestmentGrade.fromScore(40)).isEqualTo(InvestmentGrade.B);
        assertThat(InvestmentGrade.fromScore(39)).isEqualTo(InvestmentGrade.CCC);
        assertThat(InvestmentGrade.fromScore(0)).isEqualTo(InvestmentGrade.CCC);
    }
}
