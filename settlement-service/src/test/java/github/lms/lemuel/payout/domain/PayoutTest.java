package github.lms.lemuel.payout.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    @Test
    @DisplayName("startSending: REQUESTED 가 아니면 거부")
    void startSending_onlyFromRequested() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.startSending();
        // SENDING 에서 재차 startSending
        assertThatThrownBy(p::startSending).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markCompleted: SENDING 이 아니면 거부")
    void markCompleted_onlyFromSending() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        // REQUESTED 에서 바로 완료 시도
        assertThatThrownBy(() -> p.markCompleted("FB-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markFailed: SENDING 이 아니면 거부 + 사유 필수")
    void markFailed_guards() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        assertThatThrownBy(() -> p.markFailed("err")).isInstanceOf(IllegalStateException.class);

        p.startSending();
        assertThatThrownBy(() -> p.markFailed(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.markFailed(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cancel: REQUESTED 에서도 직접 취소 가능")
    void cancel_fromRequested() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);

        p.cancel("ops", "요청 철회");

        assertThat(p.getStatus()).isEqualTo(PayoutStatus.CANCELED);
        assertThat(p.isFinal()).isTrue();
    }

    @Test
    @DisplayName("cancel: SENDING 중에는 취소 불가 (송금 진행 중)")
    void cancel_notFromSending() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.startSending();

        assertThatThrownBy(() -> p.cancel("ops", "사유")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("isRetryable: FAILED 만 true")
    void isRetryable() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        assertThat(p.isRetryable()).isFalse();

        p.startSending();
        p.markFailed("err");
        assertThat(p.isRetryable()).isTrue();
        assertThat(p.isFinal()).isFalse();
    }

    @Test
    @DisplayName("assignId: 최초 1회만 허용, 재부여는 거부")
    void assignId_onlyOnce() {
        Payout p = Payout.requestFromSettlement(1L, 1L, new BigDecimal("100"), ACCOUNT);
        p.assignId(55L);
        assertThat(p.getId()).isEqualTo(55L);

        assertThatThrownBy(() -> p.assignId(56L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rehydrate: 영속 상태에서 전 필드를 복원")
    void rehydrate_restoresAllFields() {
        LocalDateTime t = LocalDateTime.of(2026, 3, 10, 1, 2, 3);
        Payout p = Payout.rehydrate(9L, 100L, 7L, new BigDecimal("50000"), ACCOUNT,
                PayoutStatus.COMPLETED, "FB-xyz", null, 2, "ops-1",
                t, t.plusMinutes(1), t.plusMinutes(2), null, t, t.plusMinutes(2));

        assertThat(p.getId()).isEqualTo(9L);
        assertThat(p.getSettlementId()).isEqualTo(100L);
        assertThat(p.getSellerId()).isEqualTo(7L);
        assertThat(p.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(p.getFirmBankingTransactionId()).isEqualTo("FB-xyz");
        assertThat(p.getRetryCount()).isEqualTo(2);
        assertThat(p.getOperatorId()).isEqualTo("ops-1");
        assertThat(p.getRequestedAt()).isEqualTo(t);
        assertThat(p.getSentAt()).isEqualTo(t.plusMinutes(1));
        assertThat(p.getCompletedAt()).isEqualTo(t.plusMinutes(2));
        assertThat(p.getFailedAt()).isNull();
        assertThat(p.getCreatedAt()).isEqualTo(t);
        assertThat(p.getUpdatedAt()).isEqualTo(t.plusMinutes(2));
    }
}
