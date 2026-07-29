package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 개인·법인 공통 차주 VO 규칙.
 *
 * <p>기존 {@code CorporateLoan} 이 상장사 종목코드(6자리)에 정체성을 묶은 것과 달리, 담보대출은
 * 개인도 차주가 될 수 있어야 하므로 차주 개념을 별도 VO 로 분리한다. 소유권 스코핑(IDOR 방지)을 위해
 * {@code userId}(JWT 주체)는 유형과 무관하게 항상 필수다.
 */
class BorrowerTest {

    // ─── 생성 ────────────────────────────────────────────────────────────────

    @Test
    void 개인차주는_사업자번호_없이_생성된다() {
        Borrower borrower = Borrower.individual(42L, "홍길동");

        assertThat(borrower.type()).isEqualTo(BorrowerType.INDIVIDUAL);
        assertThat(borrower.userId()).isEqualTo(42L);
        assertThat(borrower.name()).isEqualTo("홍길동");
        assertThat(borrower.registrationNo()).isNull();
    }

    @Test
    void 법인차주는_사업자번호를_갖는다() {
        Borrower borrower = Borrower.corporate(7L, "레무엘커머스", "1234567890");

        assertThat(borrower.type()).isEqualTo(BorrowerType.CORPORATE);
        assertThat(borrower.userId()).isEqualTo(7L);
        assertThat(borrower.registrationNo()).isEqualTo("1234567890");
    }

    @Test
    void 사업자번호의_하이픈은_제거해_정규화한다() {
        Borrower borrower = Borrower.corporate(7L, "레무엘커머스", "123-45-67890");

        assertThat(borrower.registrationNo()).isEqualTo("1234567890");
    }

    @Test
    void 이름_앞뒤_공백은_제거한다() {
        assertThat(Borrower.individual(1L, "  홍길동  ").name()).isEqualTo("홍길동");
    }

    // ─── 불변식 ──────────────────────────────────────────────────────────────

    @Test
    void userId_가_없으면_예외() {
        assertThatThrownBy(() -> Borrower.individual(null, "홍길동"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 이름이_비어있으면_예외() {
        assertThatThrownBy(() -> Borrower.individual(1L, "   "))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 이름이_null_이면_예외() {
        assertThatThrownBy(() -> Borrower.individual(1L, null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 차주유형이_없으면_예외() {
        assertThatThrownBy(() -> new Borrower(null, 1L, "홍길동", null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 법인차주에_사업자번호가_없으면_예외() {
        assertThatThrownBy(() -> Borrower.corporate(1L, "레무엘", null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 개인차주에_사업자번호를_주면_예외() {
        assertThatThrownBy(() -> new Borrower(BorrowerType.INDIVIDUAL, 1L, "홍길동", "1234567890"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 사업자번호가_10자리가_아니면_예외() {
        assertThatThrownBy(() -> Borrower.corporate(1L, "레무엘", "12345"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 사업자번호에_숫자가_아닌_문자가_섞이면_예외() {
        assertThatThrownBy(() -> Borrower.corporate(1L, "레무엘", "12345A7890"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    // ─── 값 동등성 ────────────────────────────────────────────────────────────

    @Test
    void 같은_값이면_동등하다() {
        assertThat(Borrower.individual(1L, "홍길동")).isEqualTo(Borrower.individual(1L, "홍길동"));
    }

    @Test
    void 유형이_다르면_동등하지_않다() {
        assertThat(Borrower.individual(1L, "레무엘"))
                .isNotEqualTo(Borrower.corporate(1L, "레무엘", "1234567890"));
    }

    // ─── 조회 편의 ────────────────────────────────────────────────────────────

    @Test
    void 개인여부를_알려준다() {
        assertThat(Borrower.individual(1L, "홍길동").isIndividual()).isTrue();
        assertThat(Borrower.corporate(1L, "레무엘", "1234567890").isIndividual()).isFalse();
    }
}
