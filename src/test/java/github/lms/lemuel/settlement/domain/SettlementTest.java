package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Settlement 도메인")
class SettlementTest {

    // ── 팩토리 ──────────────────────────────────────────────────────
    @Nested
    @DisplayName("createFromPayment 팩토리 메서드")
    class CreateFromPayment {

        @Test
        @DisplayName("유효한 값으로 정산을 생성한다")
        void create_withValidData_succeeds() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.of(2026, 1, 1));

            assertThat(s.getPaymentId()).isEqualTo(1L);
            assertThat(s.getOrderId()).isEqualTo(10L);
            assertThat(s.getPaymentAmount()).isEqualByComparingTo("100000");
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
            assertThat(s.getSettlementDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("수수료 3%와 순 지급액을 자동 계산한다")
        void create_calculatesCommissionAndNetAmount() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.now());

            assertThat(s.getCommission()).isEqualByComparingTo("3000.00");
            assertThat(s.getNetAmount()).isEqualByComparingTo("97000.00");
        }

        @Test
        @DisplayName("소수점 결제 금액의 수수료를 HALF_UP으로 반올림한다")
        void create_roundsCommissionHalfUp() {
            // 10001 * 0.03 = 300.03 → 300.03
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("10001"), LocalDate.now());

            assertThat(s.getCommission()).isEqualByComparingTo("300.03");
            assertThat(s.getNetAmount()).isEqualByComparingTo("9700.97");
        }

        @Test
        @DisplayName("paymentId가 null이면 예외를 던진다")
        void create_nullPaymentId_throws() {
            assertThatThrownBy(() ->
                Settlement.createFromPayment(null, 10L, new BigDecimal("100000"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment ID");
        }

        @Test
        @DisplayName("paymentId가 0이면 예외를 던진다")
        void create_zeroPaymentId_throws() {
            assertThatThrownBy(() ->
                Settlement.createFromPayment(0L, 10L, new BigDecimal("100000"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("결제 금액이 0이면 예외를 던진다")
        void create_zeroAmount_throws() {
            assertThatThrownBy(() ->
                Settlement.createFromPayment(1L, 10L, BigDecimal.ZERO, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount must be greater than zero");
        }

        @Test
        @DisplayName("결제 금액이 음수이면 예외를 던진다")
        void create_negativeAmount_throws() {
            assertThatThrownBy(() ->
                Settlement.createFromPayment(1L, 10L, new BigDecimal("-1"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("정산일이 null이면 예외를 던진다")
        void create_nullSettlementDate_throws() {
            assertThatThrownBy(() ->
                Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Settlement date is required");
        }
    }

    // ── 상태 머신 ────────────────────────────────────────────────────
    @Nested
    @DisplayName("상태 전이 (상태 머신)")
    class StateMachine {

        @Test
        @DisplayName("REQUESTED → startProcessing() → PROCESSING")
        void startProcessing_fromRequested_becomesProcessing() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());

            s.startProcessing();

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.PROCESSING);
            assertThat(s.isProcessing()).isTrue();
        }

        @Test
        @DisplayName("PROCESSING이 아닌 상태에서 startProcessing() 호출 시 예외")
        void startProcessing_fromNonRequested_throws() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
            s.startProcessing(); // PROCESSING 상태로 변경

            assertThatThrownBy(s::startProcessing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROCESSING");
        }

        @Test
        @DisplayName("PROCESSING → complete() → DONE")
        void complete_fromProcessing_becomesDone() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
            s.startProcessing();

            s.complete();

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);
            assertThat(s.isDone()).isTrue();
            assertThat(s.getConfirmedAt()).isNotNull();
        }

        @Test
        @DisplayName("PROCESSING이 아닌 상태에서 complete() 호출 시 예외")
        void complete_fromNonProcessing_throws() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
            // REQUESTED 상태에서 complete 불가
            assertThatThrownBy(s::complete)
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("PROCESSING → fail() → FAILED")
        void fail_fromProcessing_becomesFailed() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
            s.startProcessing();

            s.fail("처리 오류 발생");

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.FAILED);
            assertThat(s.getFailureReason()).isEqualTo("처리 오류 발생");
            assertThat(s.canRetry()).isTrue();
        }

        @Test
        @DisplayName("FAILED → retry() → REQUESTED")
        void retry_fromFailed_becomesRequested() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
            s.startProcessing();
            s.fail("오류");

            s.retry();

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
            assertThat(s.getFailureReason()).isNull();
        }

        @Test
        @DisplayName("FAILED가 아닌 상태에서 retry() 호출 시 예외")
        void retry_fromNonFailed_throws() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());

            assertThatThrownBy(s::retry)
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("DONE 상태에서 cancel() 시 예외")
        void cancel_fromDone_throws() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());
            s.startProcessing();
            s.complete();

            assertThatThrownBy(s::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED or DONE");
        }

        @Test
        @DisplayName("REQUESTED 상태에서 cancel() 가능")
        void cancel_fromRequested_becomesCanceled() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("50000"), LocalDate.now());

            s.cancel();

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
        }
    }

    // ── 환불 처리 ────────────────────────────────────────────────────
    @Nested
    @DisplayName("환불 반영 (adjustForRefund)")
    class AdjustForRefund {

        @Test
        @DisplayName("부분 환불 시 refundedAmount가 누적되고 netAmount가 재계산된다")
        void adjustForRefund_partialRefund_recalculatesNetAmount() {
            // paymentAmount=100000, commission=3000, netAmount=97000
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.now());

            s.adjustForRefund(new BigDecimal("10000"));

            assertThat(s.getRefundedAmount()).isEqualByComparingTo("10000");
            // (100000 - 10000) - 3000 = 87000
            assertThat(s.getNetAmount()).isEqualByComparingTo("87000.00");
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
        }

        @Test
        @DisplayName("환불 금액이 정산 금액을 초과하면 CANCELED 상태가 된다")
        void adjustForRefund_exceedsNetAmount_becomesCanceled() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.now());

            // 100000 전액 환불 → netAmount = (100000-100000) - 3000 = -3000 → CANCELED
            s.adjustForRefund(new BigDecimal("100000"));

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
        }

        @Test
        @DisplayName("환불 금액이 정확히 netAmount와 같으면 CANCELED 상태가 된다")
        void adjustForRefund_exactNetAmount_becomesCanceled() {
            // paymentAmount=100000, netAmount=97000
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.now());

            // 97000 환불 → (100000-97000) - 3000 = 0 → CANCELED
            s.adjustForRefund(new BigDecimal("97000"));

            assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
        }

        @Test
        @DisplayName("환불 금액이 0이면 예외를 던진다")
        void adjustForRefund_zeroAmount_throws() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.now());

            assertThatThrownBy(() -> s.adjustForRefund(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Refund amount must be greater than zero");
        }

        @Test
        @DisplayName("환불 금액이 null이면 예외를 던진다")
        void adjustForRefund_nullAmount_throws() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.now());

            assertThatThrownBy(() -> s.adjustForRefund(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("환불이 누적 적용된다")
        void adjustForRefund_cumulativeRefunds() {
            Settlement s = Settlement.createFromPayment(1L, 10L, new BigDecimal("100000"), LocalDate.now());

            s.adjustForRefund(new BigDecimal("10000"));
            s.adjustForRefund(new BigDecimal("20000"));

            assertThat(s.getRefundedAmount()).isEqualByComparingTo("30000");
            // (100000 - 30000) - 3000 = 67000
            assertThat(s.getNetAmount()).isEqualByComparingTo("67000.00");
        }
    }

    // ── 상태 확인 메서드 ─────────────────────────────────────────────
    @Nested
    @DisplayName("상태 확인 메서드")
    class StatusCheckers {

        @Test
        @DisplayName("isConfirmed — CONFIRMED 상태에서만 true")
        void isConfirmed_onlyForConfirmed() {
            Settlement s = new Settlement();
            s.setStatus(SettlementStatus.CONFIRMED);
            assertThat(s.isConfirmed()).isTrue();

            s.setStatus(SettlementStatus.DONE);
            assertThat(s.isConfirmed()).isFalse();
        }

        @Test
        @DisplayName("isPending — PENDING 또는 WAITING_APPROVAL 상태에서 true")
        void isPending_forPendingAndWaitingApproval() {
            Settlement s = new Settlement();

            s.setStatus(SettlementStatus.PENDING);
            assertThat(s.isPending()).isTrue();

            s.setStatus(SettlementStatus.WAITING_APPROVAL);
            assertThat(s.isPending()).isTrue();

            s.setStatus(SettlementStatus.DONE);
            assertThat(s.isPending()).isFalse();
        }

        @Test
        @DisplayName("canRetry — FAILED 상태에서만 true")
        void canRetry_onlyForFailed() {
            Settlement s = new Settlement();
            s.setStatus(SettlementStatus.FAILED);
            assertThat(s.canRetry()).isTrue();

            s.setStatus(SettlementStatus.REQUESTED);
            assertThat(s.canRetry()).isFalse();
        }

        @Test
        @DisplayName("isDone — DONE 상태에서만 true")
        void isDone_onlyForDone() {
            Settlement s = new Settlement();
            s.setStatus(SettlementStatus.DONE);
            assertThat(s.isDone()).isTrue();

            s.setStatus(SettlementStatus.CONFIRMED);
            assertThat(s.isDone()).isFalse();
        }
    }
}