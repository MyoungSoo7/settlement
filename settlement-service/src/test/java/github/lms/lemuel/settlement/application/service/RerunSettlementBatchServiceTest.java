package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import github.lms.lemuel.settlement.application.port.out.RunSettlementConfirmBatchPort;
import github.lms.lemuel.settlement.domain.exception.InvalidRerunRequestException;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunReport;
import github.lms.lemuel.settlement.domain.rerun.SettlementRerunScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정산 배치 재실행 오케스트레이션 — 단계 디스패치·부분 실패 격리·기본 일자 보정.
 */
@ExtendWith(MockitoExtension.class)
class RerunSettlementBatchServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 6);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final int MAX_LOOKBACK_DAYS = 90;

    @Mock RunSettlementConfirmBatchPort confirmBatchPort;
    @Mock ReleaseHoldbackUseCase releaseHoldbackUseCase;
    @Mock ExecutePayoutUseCase executePayoutUseCase;

    private RerunSettlementBatchService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(
                ZonedDateTime.of(TODAY.atTime(10, 0), KST).toInstant(), KST);
        service = new RerunSettlementBatchService(
                confirmBatchPort, releaseHoldbackUseCase, executePayoutUseCase,
                fixedClock, MAX_LOOKBACK_DAYS);
    }

    @Test
    @DisplayName("CONFIRM: 확정 배치만 targetDate 로 실행하고 건수를 리포트에 담는다")
    void confirmDispatchesBatchOnly() {
        when(confirmBatchPort.runFor(YESTERDAY))
                .thenReturn(new RunSettlementConfirmBatchPort.BatchRunResult("COMPLETED", 20, 12));

        SettlementRerunReport report = service.rerun(SettlementRerunScope.CONFIRM, YESTERDAY);

        verify(confirmBatchPort).runFor(YESTERDAY);
        verify(releaseHoldbackUseCase, never()).releaseAllDueOn(any());
        verify(executePayoutUseCase, never()).executeAllPending();

        assertThat(report.complete()).isTrue();
        assertThat(report.targetDate()).isEqualTo(YESTERDAY);
        assertThat(report.totalAffected()).isEqualTo(12);
    }

    @Test
    @DisplayName("ALL: CONFIRM → HOLDBACK_RELEASE 순서로 실행하고 송금은 건드리지 않는다")
    void allRunsRecomputeStepsInOrder() {
        when(confirmBatchPort.runFor(YESTERDAY))
                .thenReturn(new RunSettlementConfirmBatchPort.BatchRunResult("COMPLETED", 20, 12));
        when(releaseHoldbackUseCase.releaseAllDueOn(YESTERDAY)).thenReturn(3);

        SettlementRerunReport report = service.rerun(SettlementRerunScope.ALL, YESTERDAY);

        var order = inOrder(confirmBatchPort, releaseHoldbackUseCase);
        order.verify(confirmBatchPort).runFor(YESTERDAY);
        order.verify(releaseHoldbackUseCase).releaseAllDueOn(YESTERDAY);
        verify(executePayoutUseCase, never()).executeAllPending();

        assertThat(report.complete()).isTrue();
        assertThat(report.totalAffected()).isEqualTo(15);
        assertThat(report.steps()).extracting(SettlementRerunReport.StepResult::scope)
                .containsExactly(SettlementRerunScope.CONFIRM, SettlementRerunScope.HOLDBACK_RELEASE);
    }

    @Test
    @DisplayName("PAYOUT_EXECUTE: 명시 지정 시에만 송금 실행 — 재계산 단계는 호출하지 않는다")
    void payoutExecuteRunsOnlyWhenNamed() {
        when(executePayoutUseCase.executeAllPending())
                .thenReturn(new ExecutePayoutUseCase.ExecutionReport(5, 1, 2));

        SettlementRerunReport report = service.rerun(SettlementRerunScope.PAYOUT_EXECUTE, YESTERDAY);

        verify(executePayoutUseCase).executeAllPending();
        verify(confirmBatchPort, never()).runFor(any());
        verify(releaseHoldbackUseCase, never()).releaseAllDueOn(any());

        assertThat(report.complete()).isTrue();
        assertThat(report.totalAffected()).isEqualTo(5);
        assertThat(report.steps().getFirst().detail()).contains("failed=1").contains("limitedSkipped=2");
    }

    @Test
    @DisplayName("부분 실패: 앞 단계가 터져도 뒤 단계는 계속 실행되고 실패가 리포트에 남는다")
    void partialFailureDoesNotStopRemainingSteps() {
        when(confirmBatchPort.runFor(YESTERDAY)).thenThrow(new RuntimeException("batch exploded"));
        when(releaseHoldbackUseCase.releaseAllDueOn(YESTERDAY)).thenReturn(3);

        SettlementRerunReport report = service.rerun(SettlementRerunScope.ALL, YESTERDAY);

        verify(releaseHoldbackUseCase).releaseAllDueOn(YESTERDAY);
        assertThat(report.complete()).isFalse();
        assertThat(report.failedSteps()).containsExactly(SettlementRerunScope.CONFIRM);
        assertThat(report.totalAffected()).isEqualTo(3);
        assertThat(report.steps()).hasSize(2);
    }

    @Test
    @DisplayName("targetDate 미지정이면 어제(KST)로 보정 — 스케줄러 기본 동작과 일치")
    void defaultsToYesterdayKst() {
        when(confirmBatchPort.runFor(YESTERDAY))
                .thenReturn(new RunSettlementConfirmBatchPort.BatchRunResult("COMPLETED", 1, 1));

        SettlementRerunReport report = service.rerun(SettlementRerunScope.CONFIRM, null);

        verify(confirmBatchPort).runFor(YESTERDAY);
        assertThat(report.targetDate()).isEqualTo(YESTERDAY);
    }

    @Test
    @DisplayName("미래 일자는 도메인 게이트가 차단 — 배치는 한 번도 호출되지 않는다")
    void futureDateRejectedBeforeAnyExecution() {
        assertThatThrownBy(() -> service.rerun(SettlementRerunScope.CONFIRM, TODAY.plusDays(1)))
                .isInstanceOf(InvalidRerunRequestException.class);

        verify(confirmBatchPort, never()).runFor(any());
        verify(releaseHoldbackUseCase, never()).releaseAllDueOn(any());
        verify(executePayoutUseCase, never()).executeAllPending();
    }

    @Test
    @DisplayName("허용 소급 범위를 넘는 과거 일자도 차단 — 대량 과거 재정산 사고 방지")
    void tooOldDateRejectedBeforeAnyExecution() {
        assertThatThrownBy(() ->
                service.rerun(SettlementRerunScope.CONFIRM, TODAY.minusDays(MAX_LOOKBACK_DAYS + 1L)))
                .isInstanceOf(InvalidRerunRequestException.class);

        verify(confirmBatchPort, never()).runFor(any());
    }

    @Test
    @DisplayName("배치가 COMPLETED 아닌 상태로 끝나면 실패로 기록 — 조용한 성공 오인 방지")
    void nonCompletedBatchStatusIsFailure() {
        when(confirmBatchPort.runFor(YESTERDAY))
                .thenReturn(new RunSettlementConfirmBatchPort.BatchRunResult("FAILED", 20, 0));

        SettlementRerunReport report = service.rerun(SettlementRerunScope.CONFIRM, YESTERDAY);

        assertThat(report.complete()).isFalse();
        assertThat(report.failedSteps()).containsExactly(SettlementRerunScope.CONFIRM);
    }

    @Test
    @DisplayName("HOLDBACK_RELEASE 단독 실행 — 해제 건수를 그대로 보고")
    void holdbackReleaseAlone() {
        when(releaseHoldbackUseCase.releaseAllDueOn(eq(YESTERDAY))).thenReturn(7);

        SettlementRerunReport report = service.rerun(SettlementRerunScope.HOLDBACK_RELEASE, YESTERDAY);

        verify(confirmBatchPort, never()).runFor(any());
        assertThat(report.totalAffected()).isEqualTo(7);
        assertThat(report.complete()).isTrue();
    }
}
