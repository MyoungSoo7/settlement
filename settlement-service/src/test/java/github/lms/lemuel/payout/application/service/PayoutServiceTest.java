package github.lms.lemuel.payout.application.service;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase.ExecutionReport;
import github.lms.lemuel.payout.application.port.out.LoadPayoutPort;
import github.lms.lemuel.payout.application.port.out.SavePayoutPort;
import github.lms.lemuel.payout.application.service.PayoutLimitChecker.Decision;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PayoutService — 정산→Payout 전환·일괄 실행·운영자 액션")
class PayoutServiceTest {

    private static final SellerBankAccount ACCOUNT =
            new SellerBankAccount("KB", "123-45-678901", "홍길동");

    @Mock LoadPayoutPort loadPort;
    @Mock SavePayoutPort savePort;
    @Mock PayoutSingleExecutor singleExecutor;
    @Mock PayoutLimitChecker limitChecker;

    PayoutService service;

    @BeforeEach
    void setUp() {
        service = new PayoutService(loadPort, savePort, singleExecutor, limitChecker,
                new SimpleMeterRegistry());
    }

    private Payout requested(Long id, Long sellerId, String amount) {
        LocalDateTime now = LocalDateTime.now();
        return Payout.rehydrate(id, 100L + id, sellerId, new BigDecimal(amount), ACCOUNT,
                PayoutStatus.REQUESTED, null, null, 0, null,
                now, null, null, null, now, now);
    }

    @Test
    @DisplayName("requestForSettlement: 신규 정산은 Payout 을 생성·저장한다")
    void request_createsWhenAbsent() {
        when(loadPort.findBySettlementId(500L)).thenReturn(Optional.empty());
        when(savePort.save(any(Payout.class))).thenAnswer(inv -> inv.getArgument(0));

        Payout result = service.requestForSettlement(500L, 7L, new BigDecimal("50000"), ACCOUNT);

        ArgumentCaptor<Payout> captor = ArgumentCaptor.forClass(Payout.class);
        verify(savePort).save(captor.capture());
        assertThat(captor.getValue().getSettlementId()).isEqualTo(500L);
        assertThat(captor.getValue().getStatus()).isEqualTo(PayoutStatus.REQUESTED);
        assertThat(result.getSellerId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("requestForSettlement: 멱등 — 이미 존재하면 저장하지 않고 기존 Payout 반환 (이중 송금 방지)")
    void request_idempotentWhenPresent() {
        Payout existing = requested(9L, 7L, "50000");
        when(loadPort.findBySettlementId(500L)).thenReturn(Optional.of(existing));

        Payout result = service.requestForSettlement(500L, 7L, new BigDecimal("50000"), ACCOUNT);

        assertThat(result).isSameAs(existing);
        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("executeAllPending: 성공·한도초과·동시성경합·실패가 각각 집계된다")
    void executeAll_countsEachOutcome() {
        Payout ok = requested(1L, 1L, "10000");
        Payout limited = requested(2L, 2L, "20000");
        Payout conflict = requested(3L, 3L, "30000");
        Payout failed = requested(4L, 4L, "40000");
        when(loadPort.findByStatus(eq(PayoutStatus.REQUESTED), anyInt()))
                .thenReturn(List.of(ok, limited, conflict, failed));

        when(limitChecker.canSend(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(new Decision(false, "셀러 일 한도 초과"));
        when(limitChecker.canSend(eq(1L), any(), any(LocalDate.class))).thenReturn(new Decision(true, null));
        when(limitChecker.canSend(eq(3L), any(), any(LocalDate.class))).thenReturn(new Decision(true, null));
        when(limitChecker.canSend(eq(4L), any(), any(LocalDate.class))).thenReturn(new Decision(true, null));

        doNothing().when(singleExecutor).execute(ok);
        doThrow(new PayoutConcurrentClaimException(3L)).when(singleExecutor).execute(conflict);
        doThrow(new RuntimeException("펌뱅킹 오류")).when(singleExecutor).execute(failed);

        ExecutionReport report = service.executeAllPending();

        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.limitedSkipped()).isEqualTo(1);
        // 한도초과 건은 펌뱅킹 실행 자체가 skip 되어야 한다
        verify(singleExecutor, never()).execute(limited);
    }

    @Test
    @DisplayName("executeAllPending: OptimisticLockingFailure 도 동시성 경합으로 흡수(실패 아님)")
    void executeAll_optimisticLockTreatedAsConflict() {
        Payout p = requested(1L, 1L, "10000");
        when(loadPort.findByStatus(eq(PayoutStatus.REQUESTED), anyInt())).thenReturn(List.of(p));
        when(limitChecker.canSend(anyLong(), any(), any(LocalDate.class))).thenReturn(new Decision(true, null));
        doThrow(new OptimisticLockingFailureException("이미 선점")).when(singleExecutor).execute(p);

        ExecutionReport report = service.executeAllPending();

        assertThat(report.succeeded()).isZero();
        assertThat(report.failed()).isZero();
    }

    @Test
    @DisplayName("executeAllPending: 대상이 없으면 0 리포트")
    void executeAll_empty() {
        when(loadPort.findByStatus(eq(PayoutStatus.REQUESTED), anyInt())).thenReturn(List.of());

        ExecutionReport report = service.executeAllPending();

        assertThat(report.succeeded()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.limitedSkipped()).isZero();
    }

    @Test
    @DisplayName("retry: FAILED Payout 을 운영자가 재시도하면 REQUESTED 로 복귀·retryCount 증가")
    void retry_success() {
        LocalDateTime now = LocalDateTime.now();
        Payout failed = Payout.rehydrate(11L, 100L, 1L, new BigDecimal("10000"), ACCOUNT,
                PayoutStatus.FAILED, null, "BANK_TIMEOUT", 0, null,
                now, now, null, now, now, now);
        when(loadPort.findById(11L)).thenReturn(Optional.of(failed));
        when(savePort.save(any(Payout.class))).thenAnswer(inv -> inv.getArgument(0));

        Payout result = service.retry(11L, "ops-alice");

        assertThat(result.getStatus()).isEqualTo(PayoutStatus.REQUESTED);
        assertThat(result.getRetryCount()).isEqualTo(1);
        assertThat(result.getOperatorId()).isEqualTo("ops-alice");
    }

    @Test
    @DisplayName("retry: 존재하지 않는 Payout 이면 IllegalArgumentException")
    void retry_notFound() {
        when(loadPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(99L, "ops"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(savePort, never()).save(any());
    }

    @Test
    @DisplayName("cancel: REQUESTED Payout 을 운영자가 사유와 함께 영구 취소")
    void cancel_success() {
        Payout p = requested(12L, 1L, "10000");
        when(loadPort.findById(12L)).thenReturn(Optional.of(p));
        when(savePort.save(any(Payout.class))).thenAnswer(inv -> inv.getArgument(0));

        Payout result = service.cancel(12L, "ops-bob", "셀러 계좌 오등록");

        assertThat(result.getStatus()).isEqualTo(PayoutStatus.CANCELED);
        assertThat(result.getFailureReason()).contains("[CANCELED by ops-bob]");
    }

    @Test
    @DisplayName("cancel: 존재하지 않는 Payout 이면 IllegalArgumentException")
    void cancel_notFound() {
        when(loadPort.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(99L, "ops", "사유"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(savePort, never()).save(any());
    }
}
