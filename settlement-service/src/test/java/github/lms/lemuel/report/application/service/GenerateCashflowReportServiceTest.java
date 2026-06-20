package github.lms.lemuel.report.application.service;

import github.lms.lemuel.report.application.port.in.GenerateCashflowReportUseCase.CashflowReportCommand;
import github.lms.lemuel.report.application.port.out.LoadCashflowAggregatePort;
import github.lms.lemuel.report.application.port.out.LoadPeriodReconciliationPort;
import github.lms.lemuel.report.domain.BucketGranularity;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.report.domain.CashflowReport;
import github.lms.lemuel.report.domain.ReconciliationCheck;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerateCashflowReportServiceTest {

    @Mock
    LoadCashflowAggregatePort loadCashflowAggregatePort;

    @Mock
    LoadPeriodReconciliationPort loadPeriodReconciliationPort;

    MeterRegistry meterRegistry;
    GenerateCashflowReportService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new GenerateCashflowReportService(
                loadCashflowAggregatePort, loadPeriodReconciliationPort, meterRegistry);
    }

    /** 3 불변식이 모두 통과하도록 포트 스텁을 주입. */
    private void stubAllInvariantsPass(LocalDate from, LocalDate to) {
        // Inv 1: 140000 - 0 = 135500 + 4500 → pass
        when(loadPeriodReconciliationPort.sumCapturedPayments(from, to))
                .thenReturn(new BigDecimal("140000"));
        when(loadPeriodReconciliationPort.sumCompletedRefunds(from, to))
                .thenReturn(BigDecimal.ZERO);
        when(loadPeriodReconciliationPort.sumSettlementNet(from, to))
                .thenReturn(new BigDecimal("135500"));
        when(loadPeriodReconciliationPort.sumSettlementCommission(from, to))
                .thenReturn(new BigDecimal("4500"));
        // Inv 2: |adjustments| == linked refunds → pass
        when(loadPeriodReconciliationPort.sumAdjustmentsAbsolute(from, to))
                .thenReturn(new BigDecimal("0"));
        when(loadPeriodReconciliationPort.sumRefundsLinkedToAdjustments(from, to))
                .thenReturn(new BigDecimal("0"));
        // Inv 3: outbox 5 == settlements 5 → pass
        when(loadPeriodReconciliationPort.countPaymentCapturedPublished(from, to))
                .thenReturn(5L);
        when(loadPeriodReconciliationPort.countSettlementsCreated(from, to))
                .thenReturn(5L);
    }

    @Test
    @DisplayName("3 불변식 모두 통과 — matched=true, checksRun=3, mismatches=0")
    void allInvariantsPass() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 3);

        List<CashflowBucket> buckets = List.of(
                new CashflowBucket(LocalDate.of(2026, 4, 1), 2,
                        new BigDecimal("50000"), BigDecimal.ZERO,
                        new BigDecimal("1500"), new BigDecimal("48500"))
        );
        when(loadCashflowAggregatePort.aggregate(from, to, BucketGranularity.DAY))
                .thenReturn(buckets);
        stubAllInvariantsPass(from, to);

        CashflowReport report = service.generate(
                new CashflowReportCommand(from, to, BucketGranularity.DAY));

        assertThat(report.reconciliation().matched()).isTrue();
        assertThat(report.reconciliation().checksRun()).isEqualTo(3);
        assertThat(report.reconciliation().mismatches()).isEmpty();
        assertThat(report.reconciliation().checks()).extracting(ReconciliationCheck::name)
                .containsExactlyInAnyOrder(
                        "payments_minus_refunds_equals_settlement",
                        "adjustments_equal_linked_refunds",
                        "outbox_published_equals_settlements_created");
    }

    @Test
    @DisplayName("Inv 1 불일치 — matched=false, mismatches 에 해당 체크 포함")
    void invariant1Mismatch() {
        LocalDate d = LocalDate.of(2026, 4, 1);
        when(loadCashflowAggregatePort.aggregate(any(), any(), any())).thenReturn(List.of());
        stubAllInvariantsPass(d, d);
        // Inv 1 을 덮어씀: payments - refunds = 100, net + commission = 99 → diff=1
        when(loadPeriodReconciliationPort.sumCapturedPayments(d, d))
                .thenReturn(new BigDecimal("100"));
        when(loadPeriodReconciliationPort.sumSettlementNet(d, d))
                .thenReturn(new BigDecimal("95"));
        when(loadPeriodReconciliationPort.sumSettlementCommission(d, d))
                .thenReturn(new BigDecimal("4"));

        CashflowReport report = service.generate(
                new CashflowReportCommand(d, d, BucketGranularity.DAY));

        assertThat(report.reconciliation().matched()).isFalse();
        assertThat(report.reconciliation().mismatches()).hasSize(1);
        assertThat(report.reconciliation().mismatches().get(0).name())
                .isEqualTo("payments_minus_refunds_equals_settlement");
    }

    @Test
    @DisplayName("Inv 2 불일치 — adjustments 와 linked refunds 합계가 다를 때 탐지")
    void invariant2Mismatch() {
        LocalDate d = LocalDate.of(2026, 4, 1);
        when(loadCashflowAggregatePort.aggregate(any(), any(), any())).thenReturn(List.of());
        stubAllInvariantsPass(d, d);
        when(loadPeriodReconciliationPort.sumAdjustmentsAbsolute(d, d))
                .thenReturn(new BigDecimal("5000"));
        when(loadPeriodReconciliationPort.sumRefundsLinkedToAdjustments(d, d))
                .thenReturn(new BigDecimal("4999"));

        CashflowReport report = service.generate(
                new CashflowReportCommand(d, d, BucketGranularity.DAY));

        assertThat(report.reconciliation().matched()).isFalse();
        assertThat(report.reconciliation().mismatches())
                .extracting(ReconciliationCheck::name)
                .containsExactly("adjustments_equal_linked_refunds");
        assertThat(report.reconciliation().mismatches().get(0).discrepancy())
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("Inv 3 불일치 — outbox 건수와 settlements 건수가 다를 때 탐지")
    void invariant3Mismatch() {
        LocalDate d = LocalDate.of(2026, 4, 1);
        when(loadCashflowAggregatePort.aggregate(any(), any(), any())).thenReturn(List.of());
        stubAllInvariantsPass(d, d);
        when(loadPeriodReconciliationPort.countPaymentCapturedPublished(d, d))
                .thenReturn(10L);
        when(loadPeriodReconciliationPort.countSettlementsCreated(d, d))
                .thenReturn(9L);

        CashflowReport report = service.generate(
                new CashflowReportCommand(d, d, BucketGranularity.DAY));

        assertThat(report.reconciliation().matched()).isFalse();
        assertThat(report.reconciliation().mismatches())
                .extracting(ReconciliationCheck::name)
                .containsExactly("outbox_published_equals_settlements_created");
    }

    @Test
    @DisplayName("여러 불변식 동시 실패 — mismatches 에 모두 포함")
    void multipleInvariantsMismatch() {
        LocalDate d = LocalDate.of(2026, 4, 1);
        when(loadCashflowAggregatePort.aggregate(any(), any(), any())).thenReturn(List.of());
        stubAllInvariantsPass(d, d);
        // Inv 1 깨뜨림
        when(loadPeriodReconciliationPort.sumSettlementNet(d, d))
                .thenReturn(new BigDecimal("999999")); // 터무니없는 값
        // Inv 3 깨뜨림
        when(loadPeriodReconciliationPort.countPaymentCapturedPublished(d, d))
                .thenReturn(100L);
        when(loadPeriodReconciliationPort.countSettlementsCreated(d, d))
                .thenReturn(0L);

        CashflowReport report = service.generate(
                new CashflowReportCommand(d, d, BucketGranularity.DAY));

        assertThat(report.reconciliation().matched()).isFalse();
        assertThat(report.reconciliation().mismatches()).hasSize(2);
    }

    @Test
    @DisplayName("Timer 메트릭이 MeterRegistry 에 등록되고 호출마다 기록된다")
    void timerIsRecorded() {
        LocalDate d = LocalDate.of(2026, 4, 1);
        when(loadCashflowAggregatePort.aggregate(any(), any(), any())).thenReturn(List.of());
        stubAllInvariantsPass(d, d);

        service.generate(new CashflowReportCommand(d, d, BucketGranularity.DAY));
        service.generate(new CashflowReportCommand(d, d, BucketGranularity.DAY));

        var timer = meterRegistry.find("cashflow_report_generation_duration_seconds").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("판매자 단위 리포트 — aggregateBySeller 호출 + reconciliation 은 빈값")
    void sellerScopedSkipsReconciliation() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 4, 3);
        Long sellerId = 42L;

        List<CashflowBucket> buckets = List.of(
                new CashflowBucket(LocalDate.of(2026, 4, 1), 1,
                        new BigDecimal("10000"), BigDecimal.ZERO,
                        new BigDecimal("300"), new BigDecimal("9700"))
        );
        when(loadCashflowAggregatePort.aggregateBySeller(from, to, BucketGranularity.DAY, sellerId))
                .thenReturn(buckets);

        CashflowReport report = service.generate(
                new CashflowReportCommand(from, to, BucketGranularity.DAY, sellerId));

        assertThat(report.buckets()).hasSize(1);
        assertThat(report.totals().gmv()).isEqualByComparingTo("10000");
        // Reconciliation 은 시스템 전체 불변식 — 판매자 단위엔 빈 값.
        assertThat(report.reconciliation().checksRun()).isZero();
        assertThat(report.reconciliation().matched()).isTrue();
        // 시스템 전체 reconciliation 포트는 호출되지 않았어야 한다.
        org.mockito.Mockito.verifyNoInteractions(loadPeriodReconciliationPort);
    }

    @Test
    @DisplayName("대사 실패 시 mismatch_total Counter 가 check 태그별로 증가")
    void mismatchCounterTaggedPerCheck() {
        LocalDate d = LocalDate.of(2026, 4, 1);
        when(loadCashflowAggregatePort.aggregate(any(), any(), any())).thenReturn(List.of());
        stubAllInvariantsPass(d, d);
        // Inv 2 를 의도적으로 깨뜨림
        when(loadPeriodReconciliationPort.sumAdjustmentsAbsolute(d, d))
                .thenReturn(new BigDecimal("100"));
        when(loadPeriodReconciliationPort.sumRefundsLinkedToAdjustments(d, d))
                .thenReturn(new BigDecimal("50"));

        service.generate(new CashflowReportCommand(d, d, BucketGranularity.DAY));
        service.generate(new CashflowReportCommand(d, d, BucketGranularity.DAY));

        var counter = meterRegistry.find("cashflow_reconciliation_mismatch_total")
                .tag("check", "adjustments_equal_linked_refunds")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);

        // 통과한 check 는 Counter 가 생성되지 않음
        var passingCounter = meterRegistry.find("cashflow_reconciliation_mismatch_total")
                .tag("check", "payments_minus_refunds_equals_settlement")
                .counter();
        assertThat(passingCounter).isNull();
    }

    @Test
    @DisplayName("from > to 는 입력 검증에서 거부")
    void rejectsInvalidRange() {
        assertThatThrownBy(() -> new CashflowReportCommand(
                LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 1), BucketGranularity.DAY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be <= to");
    }

    @Test
    @DisplayName("기간이 366일 초과면 거부")
    void rejectsTooLongPeriod() {
        assertThatThrownBy(() -> new CashflowReportCommand(
                LocalDate.of(2024, 1, 1), LocalDate.of(2025, 6, 1), BucketGranularity.MONTH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("granularity=null 이면 DAY 로 기본값")
    void defaultsToDay() {
        CashflowReportCommand cmd = new CashflowReportCommand(
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 2), null);
        assertThat(cmd.granularity()).isEqualTo(BucketGranularity.DAY);
    }
}
