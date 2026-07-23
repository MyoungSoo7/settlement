package github.lms.lemuel.integrity.adapter.in.batch;

import github.lms.lemuel.integrity.application.port.in.IntegrityQueryUseCase;
import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutBounceReconReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.RefundAdjustmentReport;
import github.lms.lemuel.integrity.domain.StuckStateReport;
import github.lms.lemuel.recon.OrderReconUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrityMonitorSchedulerTest {

    @Mock IntegrityQueryUseCase useCase;
    SimpleMeterRegistry meterRegistry;

    private static final Clock FIXED_KST = Clock.fixed(
            Instant.parse("2026-07-16T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 7, 15);

    IntegrityMonitorScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new IntegrityMonitorScheduler(useCase, meterRegistry, FIXED_KST);
    }

    private void stubAllOk() {
        lenient().when(useCase.checkLedgerCompleteness(YESTERDAY, null)).thenReturn(okLedger());
        lenient().when(useCase.checkPayoutRecon(YESTERDAY)).thenReturn(okPayout());
        lenient().when(useCase.checkHoldbackStatus()).thenReturn(okHoldback());
        lenient().when(useCase.checkStuckStates(null)).thenReturn(okStuck());
        lenient().when(useCase.checkRefundAdjustments(YESTERDAY, YESTERDAY)).thenReturn(okRefund());
        lenient().when(useCase.checkPayoutBounceRecon()).thenReturn(okPayoutBounceRecon());
    }

    @Test
    @DisplayName("전 체크 ok → violation 메트릭 0")
    void allOk_noViolation() {
        stubAllOk();

        scheduler.runDailyChecks();

        assertThat(meterRegistry.find("settlement.integrity.violation").counters()).isEmpty();
    }

    @Test
    @DisplayName("한 체크 위반 → 해당 check 의 violation{result=violation} 만 증가")
    void oneViolation_incrementsThatCheck() {
        stubAllOk();
        // payout-recon 을 과다지급 위반으로 교체
        when(useCase.checkPayoutRecon(YESTERDAY)).thenReturn(PayoutReconReport.of(
                YESTERDAY, 1, new BigDecimal("100"), 1, new BigDecimal("200"), 0,
                List.of(),
                List.of(new PayoutReconReport.OverpaidPayout(9L, 1L, new BigDecimal("200"), new BigDecimal("100"))),
                List.of(), List.of()));

        scheduler.runDailyChecks();

        assertThat(meterRegistry.counter("settlement.integrity.violation",
                "check", "payout-recon", "result", "violation").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("settlement.integrity.violation")
                .tag("check", "ledger-completeness").counter()).isNull();
    }

    @Test
    @DisplayName("INV-13 위반(고아 재지급 payout) → 해당 check 의 violation{result=violation} 만 증가")
    void payoutBounceReconViolation_incrementsThatCheck() {
        stubAllOk();
        when(useCase.checkPayoutBounceRecon()).thenReturn(
                PayoutBounceReconReport.of(0, 0, 0, List.of(), List.of(), List.of(777L)));

        scheduler.runDailyChecks();

        assertThat(meterRegistry.counter("settlement.integrity.violation",
                "check", "payout-bounce-recon", "result", "violation").count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("settlement.integrity.violation")
                .tag("check", "payout-recon").counter()).isNull();
    }

    @Test
    @DisplayName("한 체크 예외(order 무응답) → result=error 로 집계하고 나머지 체크는 계속 실행")
    void oneCheckThrows_isolatedAndOthersRun() {
        stubAllOk();
        when(useCase.checkRefundAdjustments(YESTERDAY, YESTERDAY))
                .thenThrow(new OrderReconUnavailableException("order down", new RuntimeException()));

        scheduler.runDailyChecks();

        assertThat(meterRegistry.counter("settlement.integrity.violation",
                "check", "refund-adjustments", "result", "error").count()).isEqualTo(1.0);
        // 예외가 격리돼 뒤/앞 체크는 정상 호출된다.
        verify(useCase).checkHoldbackStatus();
        verify(useCase).checkStuckStates(null);
    }

    // ── ok 리포트 팩토리 ──
    private static LedgerCompletenessReport okLedger() {
        return LedgerCompletenessReport.of(YESTERDAY, 15, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                List.of(), 0, List.of(), List.of(), 0, 0, 0);
    }

    private static PayoutReconReport okPayout() {
        return PayoutReconReport.of(YESTERDAY, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
                List.of(), List.of(), List.of(), List.of());
    }

    private static HoldbackStatusReport okHoldback() {
        return HoldbackStatusReport.of(YESTERDAY, 0, BigDecimal.ZERO, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    private static StuckStateReport okStuck() {
        return StuckStateReport.of(60, YESTERDAY, List.of(), List.of(), List.of(), List.of(), 0, 0);
    }

    private static RefundAdjustmentReport okRefund() {
        return RefundAdjustmentReport.of(YESTERDAY, YESTERDAY, 0, BigDecimal.ZERO, 0,
                List.of(), BigDecimal.ZERO, false);
    }

    private static PayoutBounceReconReport okPayoutBounceRecon() {
        return PayoutBounceReconReport.of(0, 0, 0, List.of(), List.of(), List.of());
    }
}
