package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.common.opssignal.OpsSignalCategory;
import github.lms.lemuel.common.opssignal.OpsSignalPort;
import github.lms.lemuel.recon.OrderReconUnavailableException;
import github.lms.lemuel.settlement.application.port.in.ReconcileDailyTotalsUseCase;
import github.lms.lemuel.settlement.domain.ReconciliationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyReconciliationSchedulerTest {

    @Mock ReconcileDailyTotalsUseCase reconcileUseCase;
    @Mock OpsSignalPort opsSignalPort;

    // KST 어제 = 2026-07-15
    private static final Clock FIXED_KST = Clock.fixed(
            Instant.parse("2026-07-16T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 7, 15);

    DailyReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DailyReconciliationScheduler(reconcileUseCase, opsSignalPort, FIXED_KST);
    }

    @Test
    @DisplayName("일치하면 관제 신호를 쏘지 않는다")
    void matched_noSignal() {
        when(reconcileUseCase.reconcile(YESTERDAY)).thenReturn(
                ReconciliationReport.of(YESTERDAY, new BigDecimal("100"), new BigDecimal("100"),
                        BigDecimal.ZERO, BigDecimal.ZERO));

        scheduler.reconcileYesterday();

        verify(opsSignalPort, never()).emit(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("불일치면 SETTLEMENT_FAILED(reconciliation) 관제 신호를 쏜다")
    void mismatch_emitsSignal() {
        when(reconcileUseCase.reconcile(YESTERDAY)).thenReturn(
                ReconciliationReport.of(YESTERDAY, new BigDecimal("100"), new BigDecimal("90"),
                        BigDecimal.ZERO, BigDecimal.ZERO));

        scheduler.reconcileYesterday();

        verify(opsSignalPort).emit(eq(OpsSignalCategory.SETTLEMENT_FAILED), eq("reconciliation"),
                eq("2026-07-15"), any(Map.class));
    }

    @Test
    @DisplayName("order 무응답(예외)이면 fail-soft — 예외를 삼키고 신호도 안 쏜다")
    void orderDown_failSoft() {
        when(reconcileUseCase.reconcile(YESTERDAY))
                .thenThrow(new OrderReconUnavailableException("order down", new RuntimeException()));

        // 예외가 스케줄러 밖으로 전파되지 않아야 한다(스레드 생존).
        scheduler.reconcileYesterday();

        verify(opsSignalPort, never()).emit(any(), anyString(), anyString(), any());
    }
}
