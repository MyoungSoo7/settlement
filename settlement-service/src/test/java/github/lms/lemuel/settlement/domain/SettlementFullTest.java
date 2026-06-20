package github.lms.lemuel.settlement.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class SettlementFullTest {

    private Settlement createSettlement(BigDecimal amount) {
        return Settlement.createFromPayment(1L, 10L, amount, LocalDate.now());
    }

    @Test @DisplayName("팩토리 메서드로 생성 시 수수료 3% 계산")
    void createFromPayment_calculatesCommission() {
        Settlement s = createSettlement(new BigDecimal("100000"));
        assertThat(s.getCommission()).isEqualByComparingTo("3000.00");
        assertThat(s.getNetAmount()).isEqualByComparingTo("97000.00");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
    }

    @Test @DisplayName("paymentId null이면 예외")
    void createFromPayment_nullPaymentId() {
        assertThatThrownBy(() -> Settlement.createFromPayment(null, 10L, new BigDecimal("1000"), LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Payment ID");
    }

    @Test @DisplayName("금액 0이면 예외")
    void createFromPayment_zeroAmount() {
        assertThatThrownBy(() -> Settlement.createFromPayment(1L, 10L, BigDecimal.ZERO, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test @DisplayName("정산일 null이면 예외")
    void createFromPayment_nullDate() {
        assertThatThrownBy(() -> Settlement.createFromPayment(1L, 10L, new BigDecimal("1000"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("date");
    }

    // 상태 머신 테스트
    @Test @DisplayName("REQUESTED → PROCESSING")
    void startProcessing() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.startProcessing();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PROCESSING);
    }

    @Test @DisplayName("PROCESSING → DONE")
    void complete() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.startProcessing();
        s.complete();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);
        assertThat(s.getConfirmedAt()).isNotNull();
    }

    @Test @DisplayName("PROCESSING → FAILED")
    void fail() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.startProcessing();
        s.fail("PG 오류");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.FAILED);
        assertThat(s.getFailureReason()).isEqualTo("PG 오류");
    }

    @Test @DisplayName("FAILED → REQUESTED (재시도)")
    void retry() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.startProcessing();
        s.fail("오류");
        s.retry();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
        assertThat(s.getFailureReason()).isNull();
    }

    @Test @DisplayName("DONE에서 startProcessing 불가")
    void startProcessing_fromDone_fails() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.startProcessing();
        s.complete();
        assertThatThrownBy(s::startProcessing).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("REQUESTED에서 complete 불가")
    void complete_fromRequested_fails() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        assertThatThrownBy(s::complete).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("REQUESTED에서 fail 불가")
    void fail_fromRequested_fails() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        assertThatThrownBy(() -> s.fail("err")).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("REQUESTED에서 retry 불가")
    void retry_fromRequested_fails() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        assertThatThrownBy(s::retry).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("CONFIRMED/DONE에서 cancel 불가")
    void cancel_fromDone_fails() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.startProcessing();
        s.complete();
        assertThatThrownBy(s::cancel).isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("REQUESTED에서 cancel 가능")
    void cancel_fromRequested() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.cancel();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
    }

    // 환불 테스트
    @Test @DisplayName("환불 반영 시 순지급액 재계산")
    void adjustForRefund() {
        Settlement s = createSettlement(new BigDecimal("100000"));
        // commission = 3000, netAmount = 97000
        s.adjustForRefund(new BigDecimal("20000"));
        // remaining = 100000 - 20000 = 80000, net = 80000 - 3000 = 77000
        assertThat(s.getRefundedAmount()).isEqualByComparingTo("20000");
        assertThat(s.getNetAmount()).isEqualByComparingTo("77000.00");
    }

    @Test @DisplayName("전액 환불 시 자동 취소")
    void adjustForRefund_fullRefund_canceledAutomatically() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        // commission = 300, netAmount = 9700
        s.adjustForRefund(new BigDecimal("10000"));
        // remaining = 0, net = 0 - 300 = -300 → CANCELED
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.CANCELED);
    }

    @Test @DisplayName("환불 금액 0이면 예외")
    void adjustForRefund_zeroAmount() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        assertThatThrownBy(() -> s.adjustForRefund(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("환불 금액 null이면 예외")
    void adjustForRefund_nullAmount() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        assertThatThrownBy(() -> s.adjustForRefund(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 상태 확인
    @Test @DisplayName("상태 확인 메서드")
    void statusChecks() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        assertThat(s.isProcessing()).isFalse();
        assertThat(s.isDone()).isFalse();
        assertThat(s.canRetry()).isFalse();

        s.startProcessing();
        assertThat(s.isProcessing()).isTrue();

        s.complete();
        assertThat(s.isDone()).isTrue();
    }

    @Test @DisplayName("canRetry는 FAILED에서만 true")
    void canRetry() {
        Settlement s = createSettlement(new BigDecimal("10000"));
        s.startProcessing();
        s.fail("err");
        assertThat(s.canRetry()).isTrue();
    }

    @Test @DisplayName("전체 생명주기: REQUESTED → PROCESSING → DONE")
    void fullLifecycle() {
        Settlement s = createSettlement(new BigDecimal("50000"));
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
        s.startProcessing();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PROCESSING);
        s.complete();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);
    }

    @Test @DisplayName("실패 후 재시도 생명주기: REQUESTED → PROCESSING → FAILED → REQUESTED")
    void retryLifecycle() {
        Settlement s = createSettlement(new BigDecimal("50000"));
        s.startProcessing();
        s.fail("timeout");
        s.retry();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.REQUESTED);
        s.startProcessing();
        s.complete();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DONE);
    }
}
