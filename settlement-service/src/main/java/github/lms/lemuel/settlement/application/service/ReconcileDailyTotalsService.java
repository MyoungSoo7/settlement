package github.lms.lemuel.settlement.application.service;

import github.lms.lemuel.settlement.application.port.in.ReconcileDailyTotalsUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadDailyTotalsPort;
import github.lms.lemuel.settlement.domain.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@Transactional(readOnly = true)
public class ReconcileDailyTotalsService implements ReconcileDailyTotalsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconcileDailyTotalsService.class);

    private final LoadDailyTotalsPort loadDailyTotalsPort;

    public ReconcileDailyTotalsService(LoadDailyTotalsPort loadDailyTotalsPort) {
        this.loadDailyTotalsPort = loadDailyTotalsPort;
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
            log.info("[Reconciliation] {} OK — capturedPayments={}, settlementGross={}, refundedAgainstCaptures={}, settlementRefunded={}, counts={}/{}",
                    targetDate, report.capturedPayments(), report.settlementGross(),
                    report.refundedAgainstCaptures(), report.settlementRefunded(),
                    report.capturedCount(), report.settlementCount());
        } else {
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
