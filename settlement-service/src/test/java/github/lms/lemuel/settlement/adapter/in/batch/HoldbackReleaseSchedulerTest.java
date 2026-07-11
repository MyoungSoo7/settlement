package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldbackReleaseSchedulerTest {

    @Mock ReleaseHoldbackUseCase useCase;

    @Test
    @DisplayName("releaseDue — 오늘 날짜로 releaseAllDueOn 을 호출한다")
    void releaseDue_invokesUseCaseWithToday() {
        HoldbackReleaseScheduler scheduler = new HoldbackReleaseScheduler(useCase);
        when(useCase.releaseAllDueOn(LocalDate.now())).thenReturn(3);

        scheduler.releaseDue();

        verify(useCase).releaseAllDueOn(LocalDate.now());
    }
}
