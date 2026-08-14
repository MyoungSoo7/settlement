package github.lms.lemuel.insurance.application.service;

import github.lms.lemuel.insurance.application.port.in.CloseMonthlyCommissionUseCase;
import github.lms.lemuel.insurance.application.port.out.CommissionClosingPort;
import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort;
import github.lms.lemuel.insurance.application.port.out.LoadCommissionSchedulePort.FcPaidSummary;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.domain.CommissionClosing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

/**
 * 월 수수료 마감 배치 — FC별 당월 지급 실적을 append-only 스냅샷으로 확정한다.
 *
 * <p>마감 기준은 <b>paid_at</b>(그 달에 지급된 사실) — 이후 환수 전이와 무관하다.
 * 환수는 {@code commission_clawback_triggered} 이벤트 흐름으로 별도 회계 처리된다.
 *
 * <p>멱등: 이미 마감된 (fc, 월)은 스킵. 스냅샷 저장과 이벤트 발행은 같은 tx(Outbox).
 */
@Service
@Transactional
public class MonthlyCommissionClosingService implements CloseMonthlyCommissionUseCase {

    private static final Logger log = LoggerFactory.getLogger(MonthlyCommissionClosingService.class);

    private final LoadCommissionSchedulePort loadSchedulePort;
    private final CommissionClosingPort closingPort;
    private final PublishInsuranceEventPort publishPort;

    public MonthlyCommissionClosingService(
            LoadCommissionSchedulePort loadSchedulePort,
            CommissionClosingPort closingPort,
            PublishInsuranceEventPort publishPort) {
        this.loadSchedulePort = loadSchedulePort;
        this.closingPort = closingPort;
        this.publishPort = publishPort;
    }

    @Override
    public MonthlyClosingResult closeMonth(YearMonth month) {
        int closed = 0;
        int skipped = 0;

        for (FcPaidSummary summary : loadSchedulePort.summarizePaidByFcInMonth(month)) {
            if (closingPort.existsByFcAndMonth(summary.fcId(), month)) {
                skipped++;
                continue;
            }
            CommissionClosing closing = CommissionClosing.close(
                    summary.fcId(), month, summary.totalPaidAmount(), summary.installmentCount());
            closingPort.save(closing);
            publishPort.publishCommissionMonthlyClosed(closing);
            closed++;
        }

        log.info("[MonthlyClosing] month={} 마감={} 스킵={}", month, closed, skipped);
        return new MonthlyClosingResult(closed, skipped);
    }
}
