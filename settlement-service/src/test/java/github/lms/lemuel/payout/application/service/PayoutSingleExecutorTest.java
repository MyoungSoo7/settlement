package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
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
@DisplayName("PayoutSingleExecutor — 단건 송금 트랜잭션 경계·선점·실패 처리")
class PayoutSingleExecutorTest {

    private static final SellerBankAccount ACCOUNT =
            new SellerBankAccount("KB", "123-45-678901", "홍길동");

    @Mock SavePayoutPort savePort;
    @Mock FirmBankingPort firmBanking;
    @Mock OpsSignalPort opsSignalPort;

    PayoutSingleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new PayoutSingleExecutor(savePort, firmBanking, opsSignalPort);
    }

    private Payout sending(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return Payout.rehydrate(id, 100L, 1L, new BigDecimal("50000"), ACCOUNT,
                PayoutStatus.SENDING, null, null, 0, null,
                now, now, null, null, now, now);
    }

    @Test
    @DisplayName("정상: 선점 후 펌뱅킹 성공 → COMPLETED 로 저장, ops 실패신호 없음")
    void execute_success() {
        Payout claimed = sending(1L);
        when(savePort.claimForSending(1L)).thenReturn(Optional.of(claimed));
        when(firmBanking.send(eq(ACCOUNT), any(BigDecimal.class), eq("PAYOUT-1")))
                .thenReturn("FB-txn-777");

        Payout input = sending(1L);
        executor.execute(input);

        assertThat(claimed.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(claimed.getFirmBankingTransactionId()).isEqualTo("FB-txn-777");
        verify(savePort).save(claimed);
        verify(opsSignalPort, never()).emit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("펌뱅킹 실패: FAILED 로 커밋 + ops 실패신호 emit + 예외 재던짐 (자동 재송금 차단)")
    void execute_firmBankingFailure() {
        Payout claimed = sending(2L);
        when(savePort.claimForSending(2L)).thenReturn(Optional.of(claimed));
        when(firmBanking.send(any(), any(), eq("PAYOUT-2")))
                .thenThrow(new FirmBankingException("BANK_TIMEOUT", "타임아웃"));

        Payout input = sending(2L);
        assertThatThrownBy(() -> executor.execute(input))
                .isInstanceOf(FirmBankingException.class);

        assertThat(claimed.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(claimed.getFailureReason()).contains("BANK_TIMEOUT");
        verify(savePort).save(claimed);
        // 운영 관제로 SETTLEMENT_FAILED 신호가 best-effort 로 발행되어야 한다
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(opsSignalPort).emit(eq(OpsSignalCategory.SETTLEMENT_FAILED), eq("payout"),
                eq("2"), ctx.capture());
        assertThat(ctx.getValue()).containsEntry("reason", "FIRM_BANKING");
    }

    @Test
    @DisplayName("동시성 경합: 이미 선점되어 claimForSending 이 비면 PayoutConcurrentClaimException, 펌뱅킹 미호출")
    void execute_concurrentClaim() {
        when(savePort.claimForSending(3L)).thenReturn(Optional.empty());

        Payout input = sending(3L);
        assertThatThrownBy(() -> executor.execute(input))
                .isInstanceOf(PayoutConcurrentClaimException.class);

        verify(firmBanking, never()).send(any(), any(), any());
        verify(savePort, never()).save(any());
    }
}
