package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 만기 추적 불변식: financingDays 보존 + 실행 시 dueAt = disbursedAt + financingDays 확정.
 * 자동 연체/상각 배치의 스캔 근거가 되는 값이라 경계를 도메인 단위로 고정한다.
 */
class LoanAdvanceDueTrackingTest {

    private static final BigDecimal PRINCIPAL = new BigDecimal("800000");
    private static final BigDecimal FEE = new BigDecimal("800");

    @Test
    void 신청은_financingDays를_보존하고_실행전_만기는_미정() {
        LoanAdvance loan = LoanAdvance.request(7L, PRINCIPAL, FEE, 7);
        assertThat(loan.getFinancingDays()).isEqualTo(7);
        assertThat(loan.getDisbursedAt()).isNull();
        assertThat(loan.getDueAt()).isNull();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REQUESTED);
    }

    @Test
    void 구경로_신청은_financingDays_0() {
        LoanAdvance loan = LoanAdvance.request(7L, PRINCIPAL, FEE);
        assertThat(loan.getFinancingDays()).isEqualTo(0);
    }

    @Test
    void 선지급일수가_음수면_예외() {
        assertThatThrownBy(() -> LoanAdvance.request(7L, PRINCIPAL, FEE, -1))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 실행하면_실행시각을_찍고_만기는_실행시각더하기_financingDays() {
        LoanAdvance loan = LoanAdvance.request(7L, PRINCIPAL, FEE, 7);
        loan.approve();
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 24, 9, 0);

        loan.disburse(asOf);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(loan.getDisbursedAt()).isEqualTo(asOf);
        assertThat(loan.getDueAt()).isEqualTo(asOf.plusDays(7)); // 2026-07-31T09:00
    }

    @Test
    void 만기추적_실행은_financingDays_0이면_예외() {
        // financingDays=0(구 데이터 DEFAULT/미지정) 대출을 만기 추적 실행하면 dueAt=disbursedAt 이 되어
        // 실행 즉시 만기 경과 → 스캐너가 당일 연체·상각으로 오판한다. 실행 시점에 차단한다.
        LoanAdvance loan = LoanAdvance.request(7L, PRINCIPAL, FEE); // financingDays=0
        loan.approve();
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 24, 9, 0);

        assertThatThrownBy(() -> loan.disburse(asOf))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.APPROVED); // 전이·만기 미확정
        assertThat(loan.getDueAt()).isNull();
    }

    @Test
    void 만기추적_실행은_financingDays_1이면_통과() {
        // 하한 경계(1일) — dueAt 이 disbursedAt 보다 하루 뒤라 당일 연체 오판이 없다.
        LoanAdvance loan = LoanAdvance.request(7L, PRINCIPAL, FEE, 1);
        loan.approve();
        LocalDateTime asOf = LocalDateTime.of(2026, 7, 24, 9, 0);

        loan.disburse(asOf);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(loan.getDueAt()).isEqualTo(asOf.plusDays(1));
    }

    @Test
    void 실행시각이_null이면_예외() {
        LoanAdvance loan = LoanAdvance.request(7L, PRINCIPAL, FEE, 7);
        loan.approve();
        assertThatThrownBy(() -> loan.disburse((LocalDateTime) null))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    void 구경로_실행은_시각과_만기를_찍지_않는다() {
        LoanAdvance loan = LoanAdvance.request(7L, PRINCIPAL, FEE, 7);
        loan.approve();
        loan.disburse(); // 레거시 오버로드

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(loan.getDisbursedAt()).isNull();
        assertThat(loan.getDueAt()).isNull();
    }
}
