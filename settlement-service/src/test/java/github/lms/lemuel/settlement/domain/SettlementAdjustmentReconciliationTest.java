package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SettlementAdjustment#ofReconciliation} 팩토리 규칙 검증.
 */
class SettlementAdjustmentReconciliationTest {

    @Test
    @DisplayName("ofReconciliation: 금액을 음수로 기록하고 discrepancyId 만 채운다 (refund/chargeback 은 NULL)")
    void negatesAndSetsDiscrepancyId() {
        SettlementAdjustment adj = SettlementAdjustment.ofReconciliation(
                100L, 55L, new BigDecimal("1000"), LocalDate.of(2026, 5, 2));

        assertThat(adj.getSettlementId()).isEqualTo(100L);
        assertThat(adj.getReconciliationDiscrepancyId()).isEqualTo(55L);
        assertThat(adj.getAmount()).isEqualByComparingTo("-1000"); // 감사 규약: 음수
        assertThat(adj.getRefundId()).isNull();
        assertThat(adj.getChargebackId()).isNull();
        assertThat(adj.getStatus()).isEqualTo(SettlementAdjustmentStatus.PENDING);
        assertThat(adj.getAdjustmentDate()).isEqualTo(LocalDate.of(2026, 5, 2));
    }

    @Test
    @DisplayName("ofReconciliation: 0 이하 clawback 금액은 거부")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> SettlementAdjustment.ofReconciliation(
                100L, 55L, BigDecimal.ZERO, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementAdjustment.ofReconciliation(
                100L, 55L, new BigDecimal("-1"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ofReconciliation: discrepancyId 누락/비정상은 거부")
    void rejectsInvalidDiscrepancyId() {
        assertThatThrownBy(() -> SettlementAdjustment.ofReconciliation(
                100L, null, new BigDecimal("1000"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementAdjustment.ofReconciliation(
                100L, 0L, new BigDecimal("1000"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
