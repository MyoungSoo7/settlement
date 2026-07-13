package github.lms.lemuel.loan.domain;

import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerSettlementViewTest {

    @Test
    @DisplayName("pending: PENDING 투영 생성 + 필드 보존")
    void pending_creates() {
        SellerSettlementView v = SellerSettlementView.pending(
                10L, 1L, new BigDecimal("50000"), LocalDate.of(2026, 6, 20));

        assertThat(v.getSettlementId()).isEqualTo(10L);
        assertThat(v.getSellerId()).isEqualTo(1L);
        assertThat(v.getAmount()).isEqualByComparingTo("50000");
        assertThat(v.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(v.getStatus()).isEqualTo(SettlementViewStatus.PENDING);
    }

    @Test
    @DisplayName("pending: settlementId/sellerId 가 null 이면 예외")
    void pending_requiresIds() {
        assertThatThrownBy(() -> SellerSettlementView.pending(null, 1L, BigDecimal.TEN, LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> SellerSettlementView.pending(10L, null, BigDecimal.TEN, LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("pending: 금액이 null·음수면 예외")
    void pending_rejectsBadAmount() {
        assertThatThrownBy(() -> SellerSettlementView.pending(10L, 1L, null, LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
        assertThatThrownBy(() -> SellerSettlementView.pending(10L, 1L, new BigDecimal("-1"), LocalDate.now()))
                .isInstanceOf(LoanInvariantViolationException.class);
    }

    @Test
    @DisplayName("pending: 금액 0 은 허용 (미지급 0 정산건)")
    void pending_allowsZeroAmount() {
        SellerSettlementView v = SellerSettlementView.pending(10L, 1L, BigDecimal.ZERO, LocalDate.now());
        assertThat(v.getAmount()).isEqualByComparingTo("0");
        assertThat(v.getStatus()).isEqualTo(SettlementViewStatus.PENDING);
    }

    @Test
    @DisplayName("confirm: PENDING → CONFIRMED")
    void confirm_transitions() {
        SellerSettlementView v = SellerSettlementView.pending(10L, 1L, BigDecimal.TEN, LocalDate.now());
        v.confirm();
        assertThat(v.getStatus()).isEqualTo(SettlementViewStatus.CONFIRMED);
    }

    @Test
    @DisplayName("reconstitute: 영속 상태를 그대로 복원 (검증 없이)")
    void reconstitute_restores() {
        SellerSettlementView v = SellerSettlementView.reconstitute(
                10L, 1L, new BigDecimal("100"), LocalDate.of(2026, 1, 1), SettlementViewStatus.CONFIRMED);

        assertThat(v.getSettlementId()).isEqualTo(10L);
        assertThat(v.getSellerId()).isEqualTo(1L);
        assertThat(v.getAmount()).isEqualByComparingTo("100");
        assertThat(v.getDueDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(v.getStatus()).isEqualTo(SettlementViewStatus.CONFIRMED);
    }
}
