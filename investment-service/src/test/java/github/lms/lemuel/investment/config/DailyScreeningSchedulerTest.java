package github.lms.lemuel.investment.config;

import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase;
import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase.DailyScreeningReport;
import github.lms.lemuel.investment.domain.ScreeningTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DailyScreeningScheduler — 크론 진입점은 판정을 유스케이스에 위임하고, 실패해도 죽지 않는다(fail-open).
 */
class DailyScreeningSchedulerTest {

    private static final LocalDate TUE = LocalDate.of(2026, 7, 28);

    private final RunDailyScreeningUseCase useCase = mock(RunDailyScreeningUseCase.class);
    private final DailyScreeningScheduler scheduler = new DailyScreeningScheduler(useCase);

    @Test
    @DisplayName("크론은 일일 스크리닝 유스케이스에 위임한다 — 날짜 판정은 스케줄러가 하지 않는다")
    void 유스케이스에_위임한다() {
        when(useCase.run()).thenReturn(
                DailyScreeningReport.screened(ScreeningTrigger.screen(TUE), 3));

        scheduler.runDailyScreening();

        verify(useCase).run();
    }

    @Test
    @DisplayName("스킵 판정도 예외 없이 정상 종료한다")
    void 스킵_판정도_정상_종료() {
        when(useCase.run()).thenReturn(
                DailyScreeningReport.skipped(ScreeningTrigger.skipUpToDate(TUE)));

        assertThatCode(scheduler::runDailyScreening).doesNotThrowAnyException();
        verify(useCase).run();
    }

    @Test
    @DisplayName("유스케이스가 터져도 크론은 죽지 않는다(fail-open) — 다음 실행일에 재시도")
    void 실패해도_fail_open() {
        when(useCase.run()).thenThrow(new IllegalStateException("market API 오류"));

        assertThatCode(scheduler::runDailyScreening).doesNotThrowAnyException();
    }
}
