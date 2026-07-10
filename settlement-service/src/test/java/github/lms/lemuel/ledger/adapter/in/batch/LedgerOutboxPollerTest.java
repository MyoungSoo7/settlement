package github.lms.lemuel.ledger.adapter.in.batch;

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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerOutboxPollerTest {

    @Mock ProcessLedgerOutboxPort processPort;

    LedgerOutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = new LedgerOutboxPoller(processPort);
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
        lenient().doThrow(new RuntimeException("boom")).when(processPort).execute(failing);

        poller.poll();

        verify(processPort).execute(succeeding);
        verify(processPort).markDone(succeeding.id());
        verify(processPort).execute(failing);
        verify(processPort).markFailed(eq(failing.id()), eq("boom"));
        verify(processPort, never()).markDone(failing.id());
    }
}
