package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort;
import github.lms.lemuel.payout.application.port.out.FirmBankingPort.FirmBankingException;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PayoutSingleExecutor} 오케스트레이션 검증 — 3-phase(선점 → 트랜잭션 밖 송금 → 확정) 순서와
 * 실패·경합·확정 tx 실패 경로. 트랜잭션 경계 자체(짧은 REQUIRES_NEW 커밋)는 {@link PayoutTxStepsTest} 가 담당.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PayoutSingleExecutor — 2-phase 집행 오케스트레이션·감사")
class PayoutSingleExecutorTest {

    private static final SellerBankAccount ACCOUNT =
            new SellerBankAccount("KB", "123-45-678901", "홍길동");

    @Mock PayoutTxSteps txSteps;
    @Mock FirmBankingPort firmBanking;
    @Mock AuditLogger auditLogger;

    PayoutSingleExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new PayoutSingleExecutor(txSteps, firmBanking, auditLogger);
    }

    private Payout requested(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return Payout.rehydrate(id, 100L, 1L, new BigDecimal("50000"), ACCOUNT,
                PayoutStatus.REQUESTED, null, null, 0, null,
                now, null, null, null, now, now);
    }

    private Payout sending(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return Payout.rehydrate(id, 100L, 1L, new BigDecimal("50000"), ACCOUNT,
                PayoutStatus.SENDING, null, null, 0, null,
                now, now, null, null, now, now);
    }

    @Test
    @DisplayName("정상: claim(커밋된 SENDING) → 트랜잭션 밖 send → markCompleted 순서, PAYOUT_EXECUTED(COMPLETED) 감사")
    void execute_success() {
        Payout sending = sending(1L);
        when(txSteps.claim(1L)).thenReturn(sending);
        when(firmBanking.send(eq(ACCOUNT), any(BigDecimal.class), eq("PAYOUT-1")))
                .thenReturn("FB-txn-777");

        executor.execute(requested(1L));

        // 선점 커밋 → 송금 → 완료 확정 순서 (송금은 확정 트랜잭션들 사이에서 일어난다)
        InOrder order = inOrder(txSteps, firmBanking);
        order.verify(txSteps).claim(1L);
        order.verify(firmBanking).send(eq(ACCOUNT), any(BigDecimal.class), eq("PAYOUT-1"));
        order.verify(txSteps).markCompleted(sending, "FB-txn-777");
        verify(txSteps, never()).markFailed(any(), any());
        verify(auditLogger).record(eq(AuditAction.PAYOUT_EXECUTED), eq("Payout"), eq("1"),
                contains("\"outcome\":\"COMPLETED\""));
    }

    @Test
    @DisplayName("펌뱅킹 실패: markFailed 로 FAILED 커밋 후 예외 재던짐, PAYOUT_EXECUTED(FAILED) 감사")
    void execute_firmBankingFailure() {
        Payout sending = sending(2L);
        when(txSteps.claim(2L)).thenReturn(sending);
        FirmBankingException failure = new FirmBankingException("BANK_TIMEOUT", "타임아웃");
        when(firmBanking.send(any(), any(), eq("PAYOUT-2"))).thenThrow(failure);

        assertThatThrownBy(() -> executor.execute(requested(2L)))
                .isInstanceOf(FirmBankingException.class);

        verify(txSteps).markFailed(sending, failure);
        verify(txSteps, never()).markCompleted(any(), any());
        verify(auditLogger).record(eq(AuditAction.PAYOUT_EXECUTED), eq("Payout"), eq("2"),
                contains("\"outcome\":\"FAILED\""));
    }

    @Test
    @DisplayName("동시성 경합: claim 이 PayoutConcurrentClaimException 이면 펌뱅킹 미호출, 확정·감사 없음")
    void execute_concurrentClaim() {
        when(txSteps.claim(3L)).thenThrow(new PayoutConcurrentClaimException(3L));

        assertThatThrownBy(() -> executor.execute(requested(3L)))
                .isInstanceOf(PayoutConcurrentClaimException.class);

        verify(firmBanking, never()).send(any(), any(), any());
        verify(txSteps, never()).markCompleted(any(), any());
        verify(txSteps, never()).markFailed(any(), any());
        verify(auditLogger, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("send 성공 후 확정 tx 실패: 예외 전파되어도 send 는 정확히 1회 — 오케스트레이터가 재송금하지 않는다(재송금 0)")
    void execute_confirmTxFailsAfterSend_noResend() {
        // 시나리오: 펌뱅킹 송금은 성공(txnId 반환)했으나, 완료 확정 트랜잭션(markCompleted) 커밋이 실패한다.
        // 이때 오케스트레이터는 send 를 다시 호출하지 않고 예외를 전파한다. 행은 SENDING 으로 남고(REQUESTED 아님),
        // REQUESTED 만 조회하는 배치가 다시 집지 않으므로 자동 재송금이 원천 차단된다(→ 배치 관점 검증은 IT).
        Payout sending = sending(5L);
        when(txSteps.claim(5L)).thenReturn(sending);
        when(firmBanking.send(any(), any(), eq("PAYOUT-5"))).thenReturn("FB-txn-999");
        org.mockito.Mockito.doThrow(new org.springframework.dao.OptimisticLockingFailureException("확정 tx 실패"))
                .when(txSteps).markCompleted(sending, "FB-txn-999");

        assertThatThrownBy(() -> executor.execute(requested(5L)))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);

        // 핵심 불변식: send 는 단 1회. 오케스트레이터 안에서 재송금(2회째 send)이나 재선점이 없다.
        verify(firmBanking, times(1)).send(any(), any(), eq("PAYOUT-5"));
        verify(txSteps, times(1)).claim(5L);
        verify(txSteps, never()).markFailed(any(), any());
    }
}
