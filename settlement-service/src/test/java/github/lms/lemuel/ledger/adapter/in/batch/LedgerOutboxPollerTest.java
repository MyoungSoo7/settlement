package github.lms.lemuel.ledger.adapter.in.batch;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.ledger.application.port.in.ProcessLedgerOutboxPort;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import github.lms.lemuel.ledger.domain.LedgerTaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerOutboxPollerTest {

    @Mock ProcessLedgerOutboxPort processPort;
    @Mock OpsSignalPort opsSignalPort;

    LedgerOutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = new LedgerOutboxPoller(processPort, opsSignalPort);
    }

    @Test
    @DisplayName("대기 작업이 없으면 즉시 반환한다")
    void poll_emptyBatch_returnsImmediately() {
        when(processPort.fetchPending(100)).thenReturn(List.of());

        poller.poll();

        verify(processPort, never()).markDone(org.mockito.ArgumentMatchers.anyLong());
        verify(processPort, never()).markFailed(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("성공한 작업은 markDone, 실패한 작업은 markFailed 를 각각 별도로 호출한다")
    void poll_mixedResults_marksDoneAndFailedSeparately() {
        LedgerOutboxTask succeeding = new LedgerOutboxTask(10L, LedgerTaskType.CREATE_ENTRY, 1L, null, null, null, 0);
        LedgerOutboxTask failing = new LedgerOutboxTask(20L, LedgerTaskType.CREATE_ENTRY, 2L, null, null, null, 0);
        when(processPort.fetchPending(100)).thenReturn(List.of(succeeding, failing));
        when(processPort.maxRetry()).thenReturn(10);
        lenient().doThrow(new RuntimeException("boom")).when(processPort).execute(failing);

        poller.poll();

        verify(processPort).execute(succeeding);
        verify(processPort).markDone(succeeding.id());
        verify(processPort).execute(failing);
        verify(processPort).markFailed(eq(failing.id()), eq("boom"));
        verify(processPort, never()).markDone(failing.id());
        // 재시도 여지가 남은(retryCount 0) 실패는 관제 신호를 쏘지 않는다.
        verify(opsSignalPort, never()).emit(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("재시도 한도 소진(FAILED 전환) 실패는 관제 신호(SETTLEMENT_FAILED)를 쏜다")
    void poll_retryExhausted_emitsOpsSignal() {
        // retryCount 9 → 이번 실패로 10(=maxRetry) 도달 → FAILED 전환.
        LedgerOutboxTask exhausting = new LedgerOutboxTask(30L, LedgerTaskType.CREATE_ENTRY, 3L, null, null, null, 9);
        when(processPort.fetchPending(100)).thenReturn(List.of(exhausting));
        when(processPort.maxRetry()).thenReturn(10);
        doThrow(new RuntimeException("boom")).when(processPort).execute(exhausting);

        poller.poll();

        verify(processPort).markFailed(eq(30L), eq("boom"));
        verify(opsSignalPort).emit(eq(OpsSignalCategory.SETTLEMENT_FAILED), eq("ledger_outbox"),
                eq("30"), any(Map.class));
    }
}
