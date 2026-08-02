package github.lms.lemuel.card.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * "없음"(unknownDefault)과 "계약 밖 값"은 다르게 다뤄야 한다 — 전자는 보수적 D 로 진행,
     * 후자는 상류 계약 위반이므로 조용히 D 로 뭉개지 않고 거부해 DLT 에서 보이게 한다.
     */
    @Test
    @DisplayName("from 은 계약 밖 등급 문자열을 원문과 함께 거부한다")
    void from_rejectsUnknownGrade() {
        assertThat(ReputationGrade.from("C")).isEqualTo(ReputationGrade.C);

        assertThatThrownBy(() -> ReputationGrade.from("F"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("F");
        assertThatThrownBy(() -> ReputationGrade.from(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
