package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class SettlementAdjustmentTest {

    @Test
    @DisplayName("정상 생성: status=PENDING, amount는 양수로 보관")
    void create_ok() {
        SettlementAdjustment adj = SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("30000"), LocalDate.of(2026, 4, 26));

        assertThat(adj.getSettlementId()).isEqualTo(100L);
        assertThat(adj.getRefundId()).isEqualTo(200L);
        assertThat(adj.getAmount()).isEqualByComparingTo("30000");
        assertThat(adj.getStatus()).isEqualTo(SettlementAdjustmentStatus.PENDING);
        assertThat(adj.getAdjustmentDate()).isEqualTo(LocalDate.of(2026, 4, 26));
    }

    @Test
    @DisplayName("amount 0/음수 거부")
    void amount_must_be_positive() {
        assertThatThrownBy(() -> SettlementAdjustment.forRefund(
                100L, 200L, BigDecimal.ZERO, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("-1"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PENDING → CONFIRMED")
    void confirm_from_pending() {
        SettlementAdjustment adj = SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("30000"), LocalDate.now());
        adj.confirm();
        assertThat(adj.getStatus()).isEqualTo(SettlementAdjustmentStatus.CONFIRMED);
        assertThat(adj.getConfirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 CONFIRMED 상태에서 재호출 거부")
    void confirm_twice_rejected() {
        SettlementAdjustment adj = SettlementAdjustment.forRefund(
                100L, 200L, new BigDecimal("30000"), LocalDate.now());
        adj.confirm();
        assertThatThrownBy(adj::confirm).isInstanceOf(IllegalStateException.class);
    }
}
