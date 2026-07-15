package github.lms.lemuel.loan.domain;

import org.junit.jupiter.api.DisplayName;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CompanyReputation} 도메인 불변식(생성자 검증)·getter 단위 테스트.
 */
class CompanyReputationTest {

    @Test
    @DisplayName("정상 생성 시 모든 필드가 보관된다")
    void construct() {
        CompanyReputation r = CompanyReputation.of("005930", 55, "C", "B", LocalDate.of(2026, 7, 7));

        assertThat(r.getStockCode()).isEqualTo("005930");
        assertThat(r.getScore()).isEqualTo(55);
        assertThat(r.getGrade()).isEqualTo("C");
        assertThat(r.getPreviousGrade()).isEqualTo("B");
        assertThat(r.getSnapshotDate()).isEqualTo(LocalDate.of(2026, 7, 7));
    }

    @Test
    @DisplayName("previousGrade 는 null 허용(최초 스냅샷)")
    void nullPreviousGrade() {
        CompanyReputation r = CompanyReputation.of("005930", 100, "A", null, LocalDate.of(2026, 7, 7));
        assertThat(r.getPreviousGrade()).isNull();
        assertThat(r.getScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("종목코드가 6자리가 아니면 예외")
    void invalidStockCode() {
        assertThatThrownBy(() -> CompanyReputation.of("12", 50, "C", "B", LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CompanyReputation.of(null, 50, "C", "B", LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("점수가 0~100 범위를 벗어나면 예외")
    void invalidScore() {
        assertThatThrownBy(() -> CompanyReputation.of("005930", -1, "C", "B", LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CompanyReputation.of("005930", 101, "C", "B", LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("등급이 비어 있으면 예외")
    void blankGrade() {
        assertThatThrownBy(() -> CompanyReputation.of("005930", 50, " ", "B", LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CompanyReputation.of("005930", 50, null, "B", LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("스냅샷 일자가 null 이면 예외")
    void nullSnapshotDate() {
        assertThatThrownBy(() -> CompanyReputation.of("005930", 50, "C", "B", null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }
}
