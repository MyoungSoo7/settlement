package github.lms.lemuel.closing.application.service;

import github.lms.lemuel.closing.application.dto.MonthlyAggregateSnapshot;
import github.lms.lemuel.closing.application.dto.SellerAggregateRow;
import github.lms.lemuel.closing.application.port.out.LoadLedgerClosedPort;
import github.lms.lemuel.closing.application.port.out.LoadMonthlyAggregatePort;
import github.lms.lemuel.closing.application.port.out.LoadMonthlyClosingPort;
import github.lms.lemuel.closing.application.port.out.SaveMonthlyClosingPort;
import github.lms.lemuel.closing.domain.ClosingRunStatus;
import github.lms.lemuel.closing.domain.ClosingTotals;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;
import github.lms.lemuel.closing.domain.exception.ClosingInvariantViolationException;
import github.lms.lemuel.closing.domain.exception.MonthlyClosingFailedException;
import github.lms.lemuel.closing.domain.exception.MonthlyClosingLockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RunMonthlyClosingServiceTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);

    @Mock LoadLedgerClosedPort loadLedgerClosedPort;
    @Mock LoadMonthlyClosingPort loadClosingPort;
    @Mock LoadMonthlyAggregatePort loadAggregatePort;
    @Mock SaveMonthlyClosingPort saveClosingPort;

    private RunMonthlyClosingService service;

    @BeforeEach
    void setUp() {
        // 2026-08-08 고정 — 현재월 2026-08, 마감 대상 2026-07.
        Clock fixed = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);
        service = new RunMonthlyClosingService(
                loadLedgerClosedPort, loadClosingPort, loadAggregatePort, saveClosingPort, fixed);
    }

    private static MonthlyAggregateSnapshot snapshotOfTwoSellers() {
        return new MonthlyAggregateSnapshot(List.of(
                new SellerAggregateRow(77L, 3, new BigDecimal("100.00"), new BigDecimal("10.00"),
                        new BigDecimal("3.50"), new BigDecimal("30.00"), new BigDecimal("86.50")),
                new SellerAggregateRow(88L, 7, new BigDecimal("200.00"), BigDecimal.ZERO,
                        new BigDecimal("7.00"), BigDecimal.ZERO, new BigDecimal("193.00"))),
                1, 3);
    }

    @Test
    void 정상_마감_집계를_마트_행과_합계_스냅샷으로_적재한다() {
        when(loadClosingPort.findRun(JULY)).thenReturn(Optional.empty());
        when(loadAggregatePort.load(JULY)).thenReturn(snapshotOfTwoSellers());
        when(saveClosingPort.saveCompleted(any(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0));

        MonthlyClosingRun result = service.run(JULY, "admin");

        assertThat(result.getStatus()).isEqualTo(ClosingRunStatus.COMPLETED);
        assertThat(result.getSellerCount()).isEqualTo(2);
        assertThat(result.getSettlementCount()).isEqualTo(10);
        assertThat(result.getUnmappedCount()).isEqualTo(1);
        assertThat(result.getPendingCount()).isEqualTo(3);
        ClosingTotals totals = result.getTotals();
        assertThat(totals.grossAmount()).isEqualByComparingTo("300.00");
        assertThat(totals.commissionAmount()).isEqualByComparingTo("10.50");
        assertThat(totals.netAmount()).isEqualByComparingTo("279.50");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SellerMonthlyClosing>> rowsCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(saveClosingPort).saveCompleted(any(), rowsCaptor.capture());
        assertThat(rowsCaptor.getValue()).hasSize(2);
        assertThat(rowsCaptor.getValue().get(0).getSellerId()).isEqualTo(77L);
    }

    @Test
    void 원장_마감된_기간에_COMPLETED_마트가_있으면_재마감을_거부한다() {
        when(loadLedgerClosedPort.isLedgerClosed(JULY)).thenReturn(true);
        MonthlyClosingRun completed = MonthlyClosingRun.start(JULY, "admin", YearMonth.of(2026, 8));
        completed.complete(0, 0, 0, 0, ClosingTotals.sumOf(List.of()));
        when(loadClosingPort.findRun(JULY)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.run(JULY, "admin"))
                .isInstanceOf(MonthlyClosingLockedException.class);

        verify(loadAggregatePort, never()).load(any());
        verify(saveClosingPort, never()).saveCompleted(any(), anyList());
    }

    @Test
    void 원장_마감됐어도_마트가_없으면_최초_적재는_허용한다() {
        // COMPLETED 마트가 없으면 원장 상태는 소비되지 않는다(단락) — 시나리오 문서화용 lenient.
        org.mockito.Mockito.lenient().when(loadLedgerClosedPort.isLedgerClosed(JULY)).thenReturn(true);
        when(loadClosingPort.findRun(JULY)).thenReturn(Optional.empty());
        when(loadAggregatePort.load(JULY)).thenReturn(snapshotOfTwoSellers());
        when(saveClosingPort.saveCompleted(any(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0));

        MonthlyClosingRun result = service.run(JULY, "admin");

        assertThat(result.getStatus()).isEqualTo(ClosingRunStatus.COMPLETED);
    }

    @Test
    void 원장이_열려_있으면_COMPLETED_마트도_재마감할_수_있다() {
        when(loadLedgerClosedPort.isLedgerClosed(JULY)).thenReturn(false);
        MonthlyClosingRun completed = MonthlyClosingRun.start(JULY, "admin", YearMonth.of(2026, 8));
        completed.complete(0, 0, 0, 0, ClosingTotals.sumOf(List.of()));
        when(loadClosingPort.findRun(JULY)).thenReturn(Optional.of(completed));
        when(loadAggregatePort.load(JULY)).thenReturn(snapshotOfTwoSellers());
        when(saveClosingPort.saveCompleted(any(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0));

        MonthlyClosingRun result = service.run(JULY, "admin");

        assertThat(result.getStatus()).isEqualTo(ClosingRunStatus.COMPLETED);
    }

    @Test
    void 집계_실패는_FAILED_run_을_기록하고_실패_예외로_전파한다() {
        when(loadClosingPort.findRun(JULY)).thenReturn(Optional.empty());
        when(loadAggregatePort.load(JULY)).thenThrow(new RuntimeException("집계 쿼리 타임아웃"));

        assertThatThrownBy(() -> service.run(JULY, "scheduler"))
                .isInstanceOf(MonthlyClosingFailedException.class);

        ArgumentCaptor<MonthlyClosingRun> runCaptor = ArgumentCaptor.forClass(MonthlyClosingRun.class);
        verify(saveClosingPort).saveRun(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo(ClosingRunStatus.FAILED);
        assertThat(runCaptor.getValue().getFailureReason()).contains("집계 쿼리 타임아웃");
        verify(saveClosingPort, never()).saveCompleted(any(), anyList());
    }

    @Test
    void 당월은_마감할_수_없다() {
        assertThatThrownBy(() -> service.run(YearMonth.of(2026, 8), "admin"))
                .isInstanceOf(ClosingInvariantViolationException.class);

        verify(loadAggregatePort, never()).load(any());
        verify(saveClosingPort, never()).saveRun(any());
    }
}
