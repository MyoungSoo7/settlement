package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ReputationGradeTest {

    @Test
    @DisplayName("등급별 haircut 계수 — E 는 0 이라 심사에서 자동 탈락한다")
    void haircutByGrade() {
        assertThat(ReputationGrade.A.haircut()).isEqualByComparingTo("1.00");
        assertThat(ReputationGrade.C.haircut()).isEqualByComparingTo("0.85");
        assertThat(ReputationGrade.E.haircut()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("평판 프로젝션이 없으면 가장 보수적인 등급(D)으로 본다")
    void unknownDefaultIsD() {
        assertThat(ReputationGrade.unknownDefault()).isEqualTo(ReputationGrade.D);
    }
}
