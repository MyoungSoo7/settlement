package github.lms.lemuel.report.application.service;

import github.lms.lemuel.report.application.port.in.GenerateCashflowReportUseCase;
import github.lms.lemuel.report.application.port.out.LoadCashflowAggregatePort;
import github.lms.lemuel.report.application.port.out.LoadPeriodReconciliationPort;
import github.lms.lemuel.report.domain.CashflowBucket;
import github.lms.lemuel.report.domain.CashflowReconciliation;
import github.lms.lemuel.report.domain.CashflowReport;
import github.lms.lemuel.report.domain.ReconciliationCheck;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
public class GenerateCashflowReportService implements GenerateCashflowReportUseCase {

    private static final String METRIC_NAME = "cashflow_report_generation_duration_seconds";
    private static final String MISMATCH_METRIC = "cashflow_reconciliation_mismatch_total";

    /** 불변식 #1: 결제(캡처) - 환불 = 정산 net + 정산 commission. */
    private static final String CHECK_1_PAYMENT_SETTLEMENT = "payments_minus_refunds_equals_settlement";
    /** 불변식 #2: |Σ(adjustments)| = Σ(refunds) — 조정-환불 원장 정합성. */
    private static final String CHECK_2_ADJUSTMENTS_REFUNDS = "adjustments_equal_linked_refunds";
    /** 불변식 #3: count(outbox PaymentCaptured PUBLISHED) = count(settlements created) — 이벤트 파이프라인 원자성. */
    private static final String CHECK_3_OUTBOX_SETTLEMENTS = "outbox_published_equals_settlements_created";

    private final LoadCashflowAggregatePort loadCashflowAggregatePort;
    private final LoadPeriodReconciliationPort loadPeriodReconciliationPort;
    private final MeterRegistry meterRegistry;
    private final Timer generationTimer;

    public GenerateCashflowReportService(LoadCashflowAggregatePort loadCashflowAggregatePort,
                                         LoadPeriodReconciliationPort loadPeriodReconciliationPort,
                                         MeterRegistry meterRegistry) {
        this.loadCashflowAggregatePort = loadCashflowAggregatePort;
        this.loadPeriodReconciliationPort = loadPeriodReconciliationPort;
        this.meterRegistry = meterRegistry;
        // Timer 는 한 번 생성해 재사용 — 반복 호출 시 MeterRegistry 조회 비용 제거.
        this.generationTimer = Timer.builder(METRIC_NAME)
                .description("Cashflow report generation latency (aggregation + reconciliation)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    public CashflowReport generate(CashflowReportCommand command) {
        return generationTimer.record(() -> doGenerate(command));
    }

    private CashflowReport doGenerate(CashflowReportCommand command) {
        log.info("cashflow report requested: from={} to={} granularity={} sellerId={}",
                command.from(), command.to(), command.granularity(), command.sellerId());

        List<CashflowBucket> buckets = command.isSellerScoped()
                ? loadCashflowAggregatePort.aggregateBySeller(
                        command.from(), command.to(), command.granularity(), command.sellerId())
                : loadCashflowAggregatePort.aggregate(
                        command.from(), command.to(), command.granularity());

        // Reconciliation 은 시스템 전체 불변식이라 판매자 단위에는 의미가 없어 건너뛴다.
        CashflowReconciliation reconciliation = command.isSellerScoped()
                ? CashflowReconciliation.empty()
                : runReconciliation(command);

        if (!reconciliation.matched()) {
            // 금액이 샜음 — 즉시 감시 가능한 ERROR 레벨. Alertmanager 로 알림 연계.
            log.error("[Cashflow Reconciliation] MISMATCH from={} to={} failedChecks={}",
                    command.from(), command.to(), reconciliation.mismatches().size());
            // 실패한 각 check 별로 Counter 증가 → Alertmanager 가 rate(...mismatch_total[1h]) > 0 로 감시.
            reconciliation.mismatches().forEach(c ->
                    Counter.builder(MISMATCH_METRIC)
                            .description("Count of cashflow reconciliation check failures, tagged by check name")
                            .tag("check", c.name())
                            .register(meterRegistry)
                            .increment());
        }

        return CashflowReport.of(
                command.from(), command.to(), command.granularity(), buckets, reconciliation);
    }

    /**
     * 리포트 기간에 대한 대사 3종 실행.
     *
     * <ol>
     *   <li>#1 payments - refunds = settlement.net + commission  — 결제→정산 금액 보존</li>
     *   <li>#2 |Σ(adjustments)| = Σ(refunds linked to adjustments)  — 조정-환불 원장 정합성</li>
     *   <li>#3 count(outbox PaymentCaptured PUBLISHED) = count(settlements created)  —
     *       이벤트 파이프라인 원자성 (경계 시각 오차 있을 수 있음, 월 단위 기간 권장)</li>
     * </ol>
     */
    private CashflowReconciliation runReconciliation(CashflowReportCommand command) {
        ReconciliationCheck c1 = checkPaymentsMinusRefundsEqualsSettlement(command);
        ReconciliationCheck c2 = checkAdjustmentsEqualLinkedRefunds(command);
        ReconciliationCheck c3 = checkOutboxEqualsSettlementsCreated(command);
        return CashflowReconciliation.of(List.of(c1, c2, c3));
    }

    private ReconciliationCheck checkPaymentsMinusRefundsEqualsSettlement(CashflowReportCommand command) {
        BigDecimal payments = loadPeriodReconciliationPort.sumCapturedPayments(
                command.from(), command.to());
        BigDecimal refunds = loadPeriodReconciliationPort.sumCompletedRefunds(
                command.from(), command.to());
        BigDecimal net = loadPeriodReconciliationPort.sumSettlementNet(
                command.from(), command.to());
        BigDecimal commission = loadPeriodReconciliationPort.sumSettlementCommission(
                command.from(), command.to());

        BigDecimal expected = payments.subtract(refunds);
        BigDecimal actual = net.add(commission);

        String detail = String.format(
                "payments=%s - refunds=%s = expected=%s; settlement.net=%s + commission=%s = actual=%s",
                payments, refunds, expected, net, commission, actual);

        return ReconciliationCheck.of(CHECK_1_PAYMENT_SETTLEMENT, expected, actual, detail);
    }

    private ReconciliationCheck checkAdjustmentsEqualLinkedRefunds(CashflowReportCommand command) {
        BigDecimal adjustmentsAbs = loadPeriodReconciliationPort.sumAdjustmentsAbsolute(
                command.from(), command.to());
        BigDecimal refundsLinked = loadPeriodReconciliationPort.sumRefundsLinkedToAdjustments(
                command.from(), command.to());

        String detail = String.format(
                "|Σ(adjustments)|=%s; Σ(refunds linked)=%s",
                adjustmentsAbs, refundsLinked);

        return ReconciliationCheck.of(CHECK_2_ADJUSTMENTS_REFUNDS, adjustmentsAbs, refundsLinked, detail);
    }

    private ReconciliationCheck checkOutboxEqualsSettlementsCreated(CashflowReportCommand command) {
        long outboxCount = loadPeriodReconciliationPort.countPaymentCapturedPublished(
                command.from(), command.to());
        long settlementsCount = loadPeriodReconciliationPort.countSettlementsCreated(
                command.from(), command.to());

        String detail = String.format(
                "outbox PaymentCaptured PUBLISHED=%d; settlements created=%d (period boundary 오차 가능)",
                outboxCount, settlementsCount);

        return ReconciliationCheck.of(CHECK_3_OUTBOX_SETTLEMENTS,
                BigDecimal.valueOf(outboxCount), BigDecimal.valueOf(settlementsCount), detail);
    }
}
