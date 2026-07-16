package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ReconcileDailyTotalsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadDailyTotalsPort;
import github.lms.lemuel.settlement.domain.ReconciliationReport;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@Service
@Transactional(readOnly = true)
public class ReconcileDailyTotalsService implements ReconcileDailyTotalsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcileDailyTotalsService.class);

    /** 대사 실행 횟수 — result=matched|mismatch. 스케줄러 생존·불일치 발생률 감시. */
    private static final String METRIC_RUNS = "settlement.reconciliation.runs";
    /** 불일치 발생 카운터 — axis=capture|refund|count. 어느 축이 새는지 알람 라우팅. */
    private static final String METRIC_MISMATCH = "settlement.reconciliation.mismatch";

    private final LoadDailyTotalsPort loadDailyTotalsPort;
    private final MeterRegistry meterRegistry;

    public ReconcileDailyTotalsService(LoadDailyTotalsPort loadDailyTotalsPort,
                                       MeterRegistry meterRegistry) {
        this.loadDailyTotalsPort = loadDailyTotalsPort;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public ReconciliationReport reconcile(LocalDate targetDate) {
        if (targetDate == null) {
            throw new IllegalArgumentException("targetDate is required");
        }

        ReconciliationReport report = ReconciliationReport.of(
                targetDate,
                loadDailyTotalsPort.sumCapturedPayments(targetDate),
                loadDailyTotalsPort.sumSettlementGross(targetDate),
                loadDailyTotalsPort.sumRefundedAgainstCaptures(targetDate),
                loadDailyTotalsPort.sumSettlementRefunded(targetDate),
                loadDailyTotalsPort.countCapturedPayments(targetDate),
                loadDailyTotalsPort.countSettlementsCreated(targetDate)
        );

        if (report.matched()) {
            meterRegistry.counter(METRIC_RUNS, "result", "matched").increment();
            log.info("[Reconciliation] {} OK — capturedPayments={}, settlementGross={}, refundedAgainstCaptures={}, settlementRefunded={}, counts={}/{}",
                    targetDate, report.capturedPayments(), report.settlementGross(),
                    report.refundedAgainstCaptures(), report.settlementRefunded(),
                    report.capturedCount(), report.settlementCount());
        } else {
            meterRegistry.counter(METRIC_RUNS, "result", "mismatch").increment();
            // 어긋난 축별로 카운터를 올려 어느 축이 새는지 알람에서 즉시 구분한다.
            if (report.captureDiscrepancy().compareTo(BigDecimal.ZERO) != 0) {
                meterRegistry.counter(METRIC_MISMATCH, "axis", "capture").increment();
            }
            if (report.refundDiscrepancy().compareTo(BigDecimal.ZERO) != 0) {
                meterRegistry.counter(METRIC_MISMATCH, "axis", "refund").increment();
            }
            if (report.countDiscrepancy() != 0) {
                meterRegistry.counter(METRIC_MISMATCH, "axis", "count").increment();
            }
            // 금액이 샜음 — 즉시 감시 가능한 ERROR 레벨. 운영에서는 Alertmanager 로 연계 권장.
            log.error("[Reconciliation] {} MISMATCH captureDiscrepancy={}, refundDiscrepancy={}, countDiscrepancy={} — capturedPayments={}, settlementGross={}, refundedAgainstCaptures={}, settlementRefunded={}, counts={}/{}",
                    targetDate, report.captureDiscrepancy(), report.refundDiscrepancy(),
                    report.countDiscrepancy(),
                    report.capturedPayments(), report.settlementGross(),
                    report.refundedAgainstCaptures(), report.settlementRefunded(),
                    report.capturedCount(), report.settlementCount());
        }
        return report;
    }
}
