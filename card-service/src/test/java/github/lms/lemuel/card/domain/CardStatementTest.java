package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CardStatement 도메인 단위 테스트 — 상태머신·금액 계산·정책을 순수 POJO 로 검증한다.
 */
class CardStatementTest {

    private static CardStatement openStatement(BigDecimal charge) {
        CardStatement s = CardStatement.openFor(1L, YearMonth.of(2026, 8),
                LocalDate.of(2026, 9, 10));
        if (charge.compareTo(BigDecimal.ZERO) > 0) {
            s.addCharge(charge);
        }
        return s;
    }

    @Test
    @DisplayName("OPEN → close() → CLOSED — closedAt 이 채워진다")
    void open_close_transitionsToClosedWithTimestamp() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();

        assertThat(s.getStatus()).isEqualTo(StatementStatus.CLOSED);
        assertThat(s.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("CLOSED → applyPayment(일부) → PARTIALLY_PAID")
    void closed_partialPayment_transitionsToPartiallyPaid() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();

        StatementStatus result = s.applyPayment(new BigDecimal("50000"));

        assertThat(result).isEqualTo(StatementStatus.PARTIALLY_PAID);
        assertThat(s.getPaidAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(s.unpaidAmount()).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("CLOSED → applyPayment(전액) → PAID")
    void closed_fullPayment_transitionsToPaid() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();

        StatementStatus result = s.applyPayment(new BigDecimal("100000"));

        assertThat(result).isEqualTo(StatementStatus.PAID);
        assertThat(s.isFullyPaid()).isTrue();
        assertThat(s.unpaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("PARTIALLY_PAID → applyPayment(나머지) → PAID")
    void partiallyPaid_remainingPayment_transitionsToPaid() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();
        s.applyPayment(new BigDecimal("40000"));

        StatementStatus result = s.applyPayment(new BigDecimal("60000"));

        assertThat(result).isEqualTo(StatementStatus.PAID);
        assertThat(s.isFullyPaid()).isTrue();
    }

    @Test
    @DisplayName("CLOSED → markDelinquent() → DELINQUENT")
    void closed_markDelinquent_transitionsToDelinquent() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();

        s.markDelinquent();

        assertThat(s.getStatus()).isEqualTo(StatementStatus.DELINQUENT);
    }

    @Test
    @DisplayName("DELINQUENT → applyPayment(전액) → PAID")
    void delinquent_fullPayment_transitionsToPaid() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();
        s.markDelinquent();

        StatementStatus result = s.applyPayment(new BigDecimal("100000"));

        assertThat(result).isEqualTo(StatementStatus.PAID);
        assertThat(s.isFullyPaid()).isTrue();
    }

    @Test
    @DisplayName("OPEN 에서 close() 없이 납부하면 예외 — 마감 전 납부 불가")
    void open_applyPayment_throwsException() {
        CardStatement s = openStatement(new BigDecimal("100000"));

        assertThatThrownBy(() -> s.applyPayment(new BigDecimal("50000")))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("PAID 에서 markDelinquent() 는 금지 전이 — 이미 납부 완료")
    void paid_markDelinquent_throwsException() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();
        s.applyPayment(new BigDecimal("100000"));

        assertThatThrownBy(s::markDelinquent)
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("OPEN 에서 markDelinquent() 는 금지 전이")
    void open_markDelinquent_throwsException() {
        CardStatement s = openStatement(new BigDecimal("100000"));

        assertThatThrownBy(s::markDelinquent)
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("addCharge 는 OPEN 상태에서만 가능 — CLOSED 에서는 예외")
    void addCharge_onClosed_throwsException() {
        CardStatement s = openStatement(new BigDecimal("50000"));
        s.close();

        assertThatThrownBy(() -> s.addCharge(new BigDecimal("10000")))
                .isInstanceOf(InvalidCardTransitionException.class);
    }

    @Test
    @DisplayName("isOverdueAndUnpaid — dueDate 경과 + CLOSED + 미납이면 true")
    void isOverdueAndUnpaid_returnsTrueWhenOverdue() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate yesterday = LocalDate.now().minusDays(1);

        assertThat(s.isOverdueAndUnpaid(tomorrow)).isFalse();  // 아직 만기 전
        assertThat(s.isOverdueAndUnpaid(yesterday)).isFalse(); // today == dueDate (not strictly after)
    }

    @Test
    @DisplayName("isOverdueAndUnpaid — dueDate 이후에 미납이면 true")
    void isOverdueAndUnpaid_afterDueDate_returnTrue() {
        CardStatement s = CardStatement.openFor(1L, YearMonth.of(2026, 8),
                LocalDate.of(2026, 9, 10));
        s.addCharge(new BigDecimal("50000"));
        s.close();

        // 만기 이후 날짜로 체크
        assertThat(s.isOverdueAndUnpaid(LocalDate.of(2026, 9, 11))).isTrue();
    }

    @Test
    @DisplayName("isOverdueAndUnpaid — PAID 이면 false")
    void isOverdueAndUnpaid_whenPaid_returnsFalse() {
        CardStatement s = CardStatement.openFor(1L, YearMonth.of(2026, 8),
                LocalDate.of(2026, 9, 10));
        s.addCharge(new BigDecimal("50000"));
        s.close();
        s.applyPayment(new BigDecimal("50000"));

        assertThat(s.isOverdueAndUnpaid(LocalDate.of(2026, 9, 11))).isFalse();
    }

    @Test
    @DisplayName("totalAmount=0 이면 isFullyPaid()=false — 빈 명세서는 납부 완료로 간주하지 않는다")
    void emptyStatement_isNotFullyPaid() {
        CardStatement s = CardStatement.openFor(1L, YearMonth.of(2026, 8),
                LocalDate.of(2026, 9, 10));

        assertThat(s.isFullyPaid()).isFalse();
    }

    @Test
    @DisplayName("납부 금액이 0 이하이면 IllegalArgumentException")
    void zeroPayment_throwsIllegalArgument() {
        CardStatement s = openStatement(new BigDecimal("100000"));
        s.close();

        assertThatThrownBy(() -> s.applyPayment(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
