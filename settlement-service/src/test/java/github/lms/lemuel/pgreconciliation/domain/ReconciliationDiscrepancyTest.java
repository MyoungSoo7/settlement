package github.lms.lemuel.pgreconciliation.domain;

import org.junit.jupiter.api.DisplayName;
import github.lms.lemuel.pgreconciliation.domain.exception.InvalidReconciliationStateException;
import github.lms.lemuel.pgreconciliation.domain.exception.PgReconciliationInvariantViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationDiscrepancyTest {

    @Test
    @DisplayName("ROUNDING_DIFF 생성 시 즉시 AUTO_CORRECTED 상태 + resolvedBy=SYSTEM")
    void roundingDiff_isAutoCorrected() {
        var d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.ROUNDING_DIFF, 100L, "TOSS:1",
                new BigDecimal("9999.50"), new BigDecimal("10000.00"));

        assertThat(d.getStatus()).isEqualTo(DiscrepancyStatus.AUTO_CORRECTED);
        assertThat(d.getResolvedBy()).isEqualTo("SYSTEM");
        assertThat(d.getResolvedAt()).isNotNull();
        assertThat(d.getDifference()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("AMOUNT_MISMATCH 등 비-자동 차이는 PENDING 으로 시작")
    void amountMismatch_isPending() {
        var d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 100L, "TOSS:1",
                new BigDecimal("10000"), new BigDecimal("10500"));

        assertThat(d.getStatus()).isEqualTo(DiscrepancyStatus.PENDING);
        assertThat(d.getResolvedBy()).isNull();
        assertThat(d.getResolvedAt()).isNull();
        assertThat(d.getDifference()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("approve: PENDING → APPROVED 전이, resolvedBy/At/note 기록")
    void approve_marksApproved() {
        var d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 100L, "TOSS:1",
                new BigDecimal("10000"), new BigDecimal("10500"));

        d.approve("ops-alice", "PG 측 수수료 변경 누락 확인 — 보정");

        assertThat(d.getStatus()).isEqualTo(DiscrepancyStatus.APPROVED);
        assertThat(d.getResolvedBy()).isEqualTo("ops-alice");
        assertThat(d.getNote()).contains("PG 측 수수료");
    }

    @Test
    @DisplayName("approve: 이미 처리된 상태에서 재호출 시 IllegalStateException")
    void approve_rejectsAlreadyResolved() {
        var d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.ROUNDING_DIFF, 100L, "TOSS:1",
                new BigDecimal("10000"), new BigDecimal("10000.50"));

        // ROUNDING_DIFF 는 이미 AUTO_CORRECTED 상태
        assertThatThrownBy(() -> d.approve("ops-1", "note"))
                .isInstanceOf(InvalidReconciliationStateException.class);
    }

    @Test
    @DisplayName("reject: 사유 없으면 IllegalArgumentException")
    void reject_requiresReason() {
        var d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 100L, "TOSS:1",
                new BigDecimal("10000"), new BigDecimal("10500"));

        assertThatThrownBy(() -> d.reject("ops-1", null))
                .isInstanceOf(PgReconciliationInvariantViolationException.class);
        assertThatThrownBy(() -> d.reject("ops-1", "  "))
                .isInstanceOf(PgReconciliationInvariantViolationException.class);
    }

    @Test
    @DisplayName("reject: 정상 거절 처리")
    void reject_marksRejected() {
        var d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.AMOUNT_MISMATCH, 100L, "TOSS:1",
                new BigDecimal("10000"), new BigDecimal("10500"));

        d.reject("ops-bob", "테스트 거래로 무시");

        assertThat(d.getStatus()).isEqualTo(DiscrepancyStatus.REJECTED);
        assertThat(d.getNote()).contains("테스트 거래");
    }

    @Test
    @DisplayName("MISSING_INTERNAL: paymentId 와 internalAmount 가 null, difference 는 pgAmount - 0")
    void missingInternal_nullFields() {
        var d = ReconciliationDiscrepancy.newDiscrepancy(
                1L, DiscrepancyType.MISSING_INTERNAL, null, "TOSS:phantom",
                null, new BigDecimal("30000"));

        assertThat(d.getPaymentId()).isNull();
        assertThat(d.getInternalAmount()).isNull();
        assertThat(d.getDifference()).isEqualByComparingTo("30000");
    }
}
