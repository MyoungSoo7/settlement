package github.lms.lemuel.payout.adapter.in.batch;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayoutSchedulerTest {

    @Mock ExecutePayoutUseCase executeUseCase;

    @Test
    @DisplayName("execute — REQUESTED 상태 Payout 을 일괄 실행하고 결과를 집계한다")
    void execute_invokesExecuteAllPending() {
        PayoutScheduler scheduler = new PayoutScheduler(executeUseCase);
        when(executeUseCase.executeAllPending())
                .thenReturn(new ExecutePayoutUseCase.ExecutionReport(5, 1, 2));

        scheduler.execute();

        verify(executeUseCase).executeAllPending();
    }
}
