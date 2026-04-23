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
                loadDailyTotalsPort.sumCompletedRefunds(targetDate),
                loadDailyTotalsPort.sumSettlementNet(targetDate),
                loadDailyTotalsPort.sumSettlementCommission(targetDate)
        );

        if (report.matched()) {
            log.info("[Reconciliation] {} OK — payments={}, refunds={}, settlementNet={}, commission={}",
                    targetDate, report.totalPayments(), report.totalRefunds(),
                    report.totalSettlementNet(), report.totalSettlementCommission());
        } else {
            // 금액이 샜음 — 즉시 감시 가능한 ERROR 레벨. 운영에서는 Alertmanager 로 연계 권장.
            log.error("[Reconciliation] {} MISMATCH discrepancy={} — payments={}, refunds={}, settlementNet={}, commission={}",
                    targetDate, report.discrepancy(), report.totalPayments(), report.totalRefunds(),
                    report.totalSettlementNet(), report.totalSettlementCommission());
        }
        return report;
    }
}
