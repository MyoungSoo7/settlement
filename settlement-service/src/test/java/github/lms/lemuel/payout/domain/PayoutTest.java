package github.lms.lemuel.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutTest {

    private static final SellerBankAccount ACCOUNT = new SellerBankAccount(
            "KB", "123-45-678901", "홍길동");

    @Test
    @DisplayName("requestFromSettlement: REQUESTED 상태 + retryCount 0 + amount 양수")
    void requestFromSettlement() {
        Payout p = Payout.requestFromSettlement(100L, 1L, new BigDecimal("50000"), ACCOUNT);

        assertThat(p.getStatus()).isEqualTo(PayoutStatus.REQUESTED);
        assertThat(p.getRetryCount()).isZero();
        assertThat(p.getAmount()).isEqualByComparingTo("50000");
        assertThat(p.getAccount().bankCode()).isEqualTo("KB");
    }

    @Test
    @DisplayName("requestFromSettlement: amount 0 또는 음수 거부")
    void invalidAmount() {
        assertThatThrownBy(() -> Payout.requestFromSettlement(1L, 1L, BigDecimal.ZERO, ACCOUNT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Payout.requestFromSettlement(1L, 1L, new BigDecimal("-1"), ACCOUNT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("정상 흐름: REQUESTED → SENDING → COMPLETED")
    void fullSuccess() {
        Payout p = Payout.requestFromSettlement(100L, 1L, new BigDecimal("50000"), ACCOUNT);

        p.startSending();
        assertThat(p.getStatus()).isEqualTo(PayoutStatus.SENDING);
        assertThat(p.getSentAt()).isNotNull();

        p.markCompleted("FB-txn-123");
        assertThat(p.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(p.getFirmBankingTransactionId()).isEqualTo("FB-txn-123");
        assertThat(p.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("markCompleted: firmBankingTransactionId 비어있으면 거부 (사후 추적 보장)")
    void completed_requiresTxnId() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.startSending();

        assertThatThrownBy(() -> p.markCompleted(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.markCompleted(""))  .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("실패 흐름: SENDING → FAILED → retry → REQUESTED + retryCount 증가")
    void failureAndRetry() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.startSending();
        p.markFailed("BANK_TIMEOUT");

        assertThat(p.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("BANK_TIMEOUT");
        assertThat(p.getFailedAt()).isNotNull();

        p.retry("ops-alice");

        assertThat(p.getStatus()).isEqualTo(PayoutStatus.REQUESTED);
        assertThat(p.getRetryCount()).isEqualTo(1);
        assertThat(p.getOperatorId()).isEqualTo("ops-alice");
        // failureReason 보존 — 사후 추적
        assertThat(p.getFailureReason()).isEqualTo("BANK_TIMEOUT");
    }

    @Test
    @DisplayName("retry: FAILED 가 아닌 상태에서 거부 (이미 송금 중인 거래 재요청 차단)")
    void retry_onlyFromFailed() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        // REQUESTED 에서 retry 시도
        assertThatThrownBy(() -> p.retry("ops")).isInstanceOf(IllegalStateException.class);

        p.startSending();
        // SENDING 에서 retry 시도
        assertThatThrownBy(() -> p.retry("ops")).isInstanceOf(IllegalStateException.class);

        p.markCompleted("FB-1");
        // COMPLETED 에서 retry 시도
        assertThatThrownBy(() -> p.retry("ops")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("cancel: 운영자 취소 — 사유 필수, FAILED/REQUESTED 만 가능")
    void cancel() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.startSending();
        p.markFailed("err");

        p.cancel("ops-bob", "셀러 계좌 잘못 등록 — 재발급 후 신규 처리");

        assertThat(p.getStatus()).isEqualTo(PayoutStatus.CANCELED);
        assertThat(p.isFinal()).isTrue();
        assertThat(p.getFailureReason()).contains("[CANCELED by ops-bob]");
    }

    @Test
    @DisplayName("cancel: 사유 없으면 거부")
    void cancel_requiresReason() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.startSending(); p.markFailed("err");

        assertThatThrownBy(() -> p.cancel("ops", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.cancel("ops", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markCompleted 후엔 cancel/retry 모두 거부 — 종결 상태")
    void completedIsFinal() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.startSending();
        p.markCompleted("FB-1");

        assertThat(p.isFinal()).isTrue();
        assertThatThrownBy(() -> p.cancel("ops", "사유")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> p.retry("ops")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SellerBankAccount.maskedAccountNumber: 마지막 4자리만 노출")
    void accountMasking() {
        SellerBankAccount a = new SellerBankAccount("KB", "1234567890", "홍길동");
        assertThat(a.maskedAccountNumber()).isEqualTo("****7890");

        SellerBankAccount short_ = new SellerBankAccount("KB", "12", "X");
        assertThat(short_.maskedAccountNumber()).isEqualTo("****");
    }
}
