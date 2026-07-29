package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase.DailyScreeningReport;
import github.lms.lemuel.investment.application.port.in.ScreenRecommendationsUseCase;
import github.lms.lemuel.investment.application.port.out.LoadDailyClosesPort;
import github.lms.lemuel.investment.application.port.out.LoadScreeningRunPort;
import github.lms.lemuel.investment.application.port.out.RecordScreeningRunPort;
import github.lms.lemuel.investment.config.ScreeningProperties;
import github.lms.lemuel.investment.config.ScreeningProperties.UniverseEntry;
import github.lms.lemuel.investment.domain.DailyClose;
import github.lms.lemuel.investment.domain.ScreeningTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RunDailyScreeningService — 크론 경로의 "돌 필요가 있는가" 판단 + 위임 검증.
 *
 * <p>유니버스 종가를 모아 {@code ScreeningTriggerPolicy} 로 판정하고, SCREEN 일 때만
 * {@link ScreenRecommendationsUseCase} 에 시세 기준일로 위임한다.
 */
class RunDailyScreeningServiceTest {

    private static final LocalDate MON = LocalDate.of(2026, 7, 27);
    private static final LocalDate TUE = LocalDate.of(2026, 7, 28);

    private final ScreenRecommendationsUseCase screen = mock(ScreenRecommendationsUseCase.class);
    private final LoadDailyClosesPort loadCloses = mock(LoadDailyClosesPort.class);
    private final LoadScreeningRunPort loadScreeningRun = mock(LoadScreeningRunPort.class);
    private final RecordScreeningRunPort recordScreeningRun = mock(RecordScreeningRunPort.class);

    private RunDailyScreeningService service(UniverseEntry... universe) {
        ScreeningProperties props = new ScreeningProperties(
                List.of(universe), 3, true, "0 0 18 * * MON-FRI", "Asia/Seoul");
        return new RunDailyScreeningService(screen, loadCloses, loadScreeningRun, recordScreeningRun, props);
    }

    private static DailyClose close(LocalDate date) {
        return new DailyClose(date, new BigDecimal("50000"));
    }

    @Test
    @DisplayName("새 종가가 도착하면 시세 기준일로 스크리닝을 위임한다 — 추천일 = 종가일")
    void 새_종가면_시세기준일로_위임() {
        when(loadCloses.loadRecentYear("005930")).thenReturn(List.of(close(MON), close(TUE)));
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.of(MON));
        when(screen.screen(TUE)).thenReturn(3);

        DailyScreeningReport report = service(new UniverseEntry("005930", "반도체")).run();

        verify(screen).screen(TUE);
        assertThat(report.trigger().decision()).isEqualTo(ScreeningTrigger.Decision.SCREEN);
        assertThat(report.trigger().quoteBaseDate()).isEqualTo(TUE);
        assertThat(report.screenedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("최신 종가일 세트가 이미 있으면 스크리닝을 아예 호출하지 않는다 (휴장일 스킵)")
    void 이미_최신이면_스킵() {
        when(loadCloses.loadRecentYear("005930")).thenReturn(List.of(close(MON), close(TUE)));
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.of(TUE));

        DailyScreeningReport report = service(new UniverseEntry("005930", "반도체")).run();

        verify(screen, never()).screen(any());
        assertThat(report.trigger().decision()).isEqualTo(ScreeningTrigger.Decision.SKIP_UP_TO_DATE);
        assertThat(report.screenedCount()).isZero();
    }

    @Test
    @DisplayName("유니버스 전 종목에서 종가를 못 구하면 스크리닝하지 않는다 — 이전 세트 유지")
    void 종가를_못_구하면_스킵() {
        when(loadCloses.loadRecentYear("005930")).thenReturn(List.of());
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.of(MON));

        DailyScreeningReport report = service(new UniverseEntry("005930", "반도체")).run();

        verify(screen, never()).screen(any());
        assertThat(report.trigger().decision()).isEqualTo(ScreeningTrigger.Decision.SKIP_NO_QUOTES);
    }

    @Test
    @DisplayName("한 종목 조회가 실패해도 나머지 종목 종가로 판정한다 — 부분 실패 ≠ 전체 실패")
    void 종목_조회_실패는_해당_종목만_건너뛴다() {
        when(loadCloses.loadRecentYear("005930")).thenThrow(new IllegalStateException("market API 오류"));
        when(loadCloses.loadRecentYear("000660")).thenReturn(List.of(close(TUE)));
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.of(MON));
        when(screen.screen(TUE)).thenReturn(1);

        DailyScreeningReport report = service(
                new UniverseEntry("005930", "반도체"), new UniverseEntry("000660", "반도체")).run();

        verify(screen).screen(TUE);
        assertThat(report.trigger().quoteBaseDate()).isEqualTo(TUE);
        assertThat(report.screenedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("통과 종목 0건이어도 실행 기록을 남긴다 — 빈 세트가 재스크리닝을 유발하지 않게")
    void 빈_결과도_실행기록을_남긴다() {
        when(loadCloses.loadRecentYear("005930")).thenReturn(List.of(close(TUE)));
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.of(MON));
        when(screen.screen(TUE)).thenReturn(0);

        DailyScreeningReport report = service(new UniverseEntry("005930", "반도체")).run();

        assertThat(report.screenedCount()).isZero();
        // 추천 행이 하나도 남지 않는 경우가 정확히 회귀 지점이다(리뷰 지적 P2).
        verify(recordScreeningRun).record(TUE, 0);
    }

    @Test
    @DisplayName("스크리닝을 돌면 시세 기준일·건수를 실행 기록에 남긴다")
    void 스크리닝하면_기준일을_기록한다() {
        when(loadCloses.loadRecentYear("005930")).thenReturn(List.of(close(TUE)));
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.of(MON));
        when(screen.screen(TUE)).thenReturn(3);

        service(new UniverseEntry("005930", "반도체")).run();

        verify(recordScreeningRun).record(TUE, 3);
    }

    @Test
    @DisplayName("스킵한 실행은 기록을 남기지 않는다 — 돌지 않은 것을 돌았다고 적지 않는다")
    void 스킵은_기록하지_않는다() {
        when(loadCloses.loadRecentYear("005930")).thenReturn(List.of(close(TUE)));
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.of(TUE));

        service(new UniverseEntry("005930", "반도체")).run();

        verify(recordScreeningRun, never()).record(any(), anyInt());
    }

    @Test
    @DisplayName("저장된 세트가 없으면(최초 실행) 최신 종가일로 스크리닝한다")
    void 저장_세트가_없으면_스크리닝() {
        when(loadCloses.loadRecentYear("005930")).thenReturn(List.of(close(TUE)));
        when(loadScreeningRun.loadLatestScreenedDate()).thenReturn(Optional.empty());
        when(screen.screen(TUE)).thenReturn(2);

        DailyScreeningReport report = service(new UniverseEntry("005930", "반도체")).run();

        verify(screen).screen(TUE);
        assertThat(report.screenedCount()).isEqualTo(2);
    }
}
