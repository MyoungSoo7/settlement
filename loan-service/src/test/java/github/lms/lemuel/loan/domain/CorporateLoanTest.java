package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.InvalidLoanStateException;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorporateLoanTest {

    private CorporateLoan requested() {
        return CorporateLoan.request("005930", "삼성전자", new BigDecimal("1000000"),
                new BigDecimal("660"), 30, 82, "A");
    }

    // ─── 생성 검증 ────────────────────────────────────────────────────────────

    @Test
    void 신규신청은_REQUESTED_이고_미상환잔액은_0() {
        CorporateLoan loan = requested();
        assertThat(loan.getStatus()).isEqualTo(CorporateLoanStatus.REQUESTED);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        assertThat(loan.getStockCode()).isEqualTo("005930");
        assertThat(loan.getCorpName()).isEqualTo("삼성전자");
        assertThat(loan.getCreditScore()).isEqualTo(82);
        assertThat(loan.getCreditGrade()).isEqualTo("A");
        assertThat(loan.getTermDays()).isEqualTo(30);
        assertThat(loan.getCreatedAt()).isNotNull();
    }

    @Test
    void 종목코드가_6자리가_아니면_예외() {
        assertThatThrownBy(() -> CorporateLoan.request("12345", "X", BigDecimal.TEN, BigDecimal.ZERO, 30, 50, "C"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 원금이_0이하면_예외() {
        assertThatThrownBy(() -> CorporateLoan.request("005930", "삼성", BigDecimal.ZERO, BigDecimal.ZERO, 30, 50, "C"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 수수료가_음수면_예외() {
        assertThatThrownBy(() -> CorporateLoan.request("005930", "삼성", BigDecimal.TEN, new BigDecimal("-1"), 30, 50, "C"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 기간이_0이하면_예외() {
        assertThatThrownBy(() -> CorporateLoan.request("005930", "삼성", BigDecimal.TEN, BigDecimal.ZERO, 0, 50, "C"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 신용점수가_범위밖이면_예외() {
        assertThatThrownBy(() -> CorporateLoan.request("005930", "삼성", BigDecimal.TEN, BigDecimal.ZERO, 30, 101, "A"))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> CorporateLoan.request("005930", "삼성", BigDecimal.TEN, BigDecimal.ZERO, 30, -1, "A"))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 신용점수_경계값_0과_100은_허용된다() {
        // 범위 [0,100] 의 양 끝값은 유효 — 경계 안쪽만 거부되는 off-by-one 회귀 가드.
        assertThat(CorporateLoan.request("005930", "삼성", BigDecimal.TEN, BigDecimal.ZERO, 30, 0, "E")
                .getCreditScore()).isEqualTo(0);
        assertThat(CorporateLoan.request("005930", "삼성", BigDecimal.TEN, BigDecimal.ZERO, 30, 100, "AAA")
                .getCreditScore()).isEqualTo(100);
    }

    @Test
    void 신용등급이_비면_예외() {
        assertThatThrownBy(() -> CorporateLoan.request("005930", "삼성", BigDecimal.TEN, BigDecimal.ZERO, 30, 50, " "))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void createdAt_오버로드는_넘긴_시각을_그대로_보존하고_null이면_예외() {
        java.time.LocalDateTime at = java.time.LocalDateTime.of(2026, 7, 16, 9, 30, 0);
        CorporateLoan loan = CorporateLoan.request("005930", "삼성전자", new BigDecimal("1000000"),
                new BigDecimal("660"), 30, 82, "A", 7L, at);
        assertThat(loan.getCreatedAt()).isEqualTo(at);
        assertThat(loan.getOwnerUserId()).isEqualTo(7L);

        assertThatThrownBy(() -> CorporateLoan.request("005930", "삼성전자", new BigDecimal("1000000"),
                new BigDecimal("660"), 30, 82, "A", 7L, null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    // ─── 정상 전이 ────────────────────────────────────────────────────────────

    @Test
    void 승인_실행하면_미상환잔액은_원금더하기수수료() {
        CorporateLoan loan = requested();
        loan.approve();
        assertThat(loan.getStatus()).isEqualTo(CorporateLoanStatus.APPROVED);
        loan.disburse();
        assertThat(loan.getStatus()).isEqualTo(CorporateLoanStatus.DISBURSED);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("1000660");
    }

    @Test
    void REQUESTED에서_거절하면_REJECTED() {
        CorporateLoan loan = requested();
        loan.reject();
        assertThat(loan.getStatus()).isEqualTo(CorporateLoanStatus.REJECTED);
    }

    @Test
    void 부분상환은_잔액을_차감하고_전액상환시_REPAID() {
        CorporateLoan loan = requested();
        loan.approve();
        loan.disburse(); // outstanding=1,000,660

        BigDecimal d1 = loan.repay(new BigDecimal("600000"));
        assertThat(d1).isEqualByComparingTo("600000");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("400660");
        assertThat(loan.getStatus()).isEqualTo(CorporateLoanStatus.DISBURSED);

        // 잔액보다 큰 상환은 잔액까지만 차감(clamp) 후 REPAID
        BigDecimal d2 = loan.repay(new BigDecimal("999999"));
        assertThat(d2).isEqualByComparingTo("400660");
        assertThat(loan.getOutstanding()).isEqualByComparingTo("0");
        assertThat(loan.getStatus()).isEqualTo(CorporateLoanStatus.REPAID);
    }

    // ─── 비정상 전이 ──────────────────────────────────────────────────────────

    @Test
    void REQUESTED에서_바로_실행하면_예외() {
        assertThatThrownBy(() -> requested().disburse())
                .isInstanceOfSatisfying(InvalidLoanStateException.class, ex -> {
                    assertThat(ex.getFrom()).isEqualTo(CorporateLoanStatus.REQUESTED);
                    assertThat(ex.getTo()).isEqualTo(CorporateLoanStatus.DISBURSED);
                });
    }

    @Test
    void APPROVED에서_다시_승인하면_예외() {
        CorporateLoan loan = requested();
        loan.approve();
        assertThatThrownBy(loan::approve).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void APPROVED에서_거절하면_예외() {
        CorporateLoan loan = requested();
        loan.approve();
        assertThatThrownBy(loan::reject).isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void DISBURSED_전이전에_상환하면_예외() {
        assertThatThrownBy(() -> requested().repay(BigDecimal.TEN))
                .isInstanceOf(InvalidLoanStateException.class);
    }

    @Test
    void 상환액이_0이하면_예외() {
        CorporateLoan loan = requested();
        loan.approve();
        loan.disburse();
        assertThatThrownBy(() -> loan.repay(BigDecimal.ZERO))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> loan.repay(null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void reconstitute는_모든_필드를_복원한다() {
        CorporateLoan loan = CorporateLoan.reconstitute(7L, "000660", "SK하이닉스",
                new BigDecimal("500000"), new BigDecimal("330"), new BigDecimal("500330"),
                14, 70, "B", CorporateLoanStatus.DISBURSED, null);
        assertThat(loan.getId()).isEqualTo(7L);
        assertThat(loan.getStockCode()).isEqualTo("000660");
        assertThat(loan.getStatus()).isEqualTo(CorporateLoanStatus.DISBURSED);
        assertThat(loan.getOutstanding()).isEqualByComparingTo("500330");
    }
}
