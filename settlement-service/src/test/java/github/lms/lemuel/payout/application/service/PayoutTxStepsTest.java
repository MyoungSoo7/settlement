package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort.FirmBankingException;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PayoutTxSteps — 2-phase 커밋의 짧은 트랜잭션 단계(선점·확정·실패)")
class PayoutTxStepsTest {

    private static final SellerBankAccount ACCOUNT =
            new SellerBankAccount("KB", "123-45-678901", "홍길동");

    @Mock SavePayoutPort savePort;
    @Mock OpsSignalPort opsSignalPort;

    PayoutTxSteps steps;

    @BeforeEach
    void setUp() {
        steps = new PayoutTxSteps(savePort, opsSignalPort);
    }

    private Payout sending(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return Payout.rehydrate(id, 100L, 1L, new BigDecimal("50000"), ACCOUNT,
                PayoutStatus.SENDING, null, null, 0, null,
                now, now, null, null, now, now);
    }

    @Test
    @DisplayName("claim: 선점 성공 시 SENDING Payout 을 반환한다")
    void claim_success() {
        Payout claimed = sending(1L);
        when(savePort.claimForSending(1L)).thenReturn(Optional.of(claimed));

        Payout result = steps.claim(1L);

        assertThat(result).isSameAs(claimed);
        assertThat(result.getStatus()).isEqualTo(PayoutStatus.SENDING);
    }

    @Test
    @DisplayName("claim: 이미 다른 인스턴스가 선점(빈 결과)이면 PayoutConcurrentClaimException")
    void claim_concurrentConflict() {
        when(savePort.claimForSending(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> steps.claim(2L))
                .isInstanceOf(PayoutConcurrentClaimException.class)
                .hasMessageContaining("2");
    }

    @Test
    @DisplayName("markCompleted: SENDING → COMPLETED 로 txnId 를 붙여 저장한다")
    void markCompleted_persistsCompleted() {
        Payout sending = sending(3L);

        steps.markCompleted(sending, "FB-txn-777");

        assertThat(sending.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(sending.getFirmBankingTransactionId()).isEqualTo("FB-txn-777");
        verify(savePort).save(sending);
        verify(opsSignalPort, never()).emit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("markFailed: SENDING → FAILED 로 사유를 저장하고 운영 관제 실패 신호를 emit 한다")
    void markFailed_persistsFailedAndEmitsSignal() {
        Payout sending = sending(4L);

        steps.markFailed(sending, new FirmBankingException("BANK_TIMEOUT", "타임아웃"));

        assertThat(sending.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(sending.getFailureReason()).contains("BANK_TIMEOUT");
        verify(savePort).save(sending);

        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(opsSignalPort).emit(eq(OpsSignalCategory.SETTLEMENT_FAILED), eq("payout"),
                eq("4"), ctx.capture());
        assertThat(ctx.getValue()).containsEntry("reason", "FIRM_BANKING");
        assertThat(ctx.getValue()).containsEntry("errorCode", "BANK_TIMEOUT");
    }
}
