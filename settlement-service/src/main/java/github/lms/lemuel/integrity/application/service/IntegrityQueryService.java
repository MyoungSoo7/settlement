package github.lms.lemuel.integrity.application.service;

import github.lms.lemuel.integrity.application.port.in.IntegrityQueryUseCase;
import github.lms.lemuel.integrity.application.port.out.IntegrityQueryPort;
import github.lms.lemuel.integrity.application.port.out.LoadCompletedRefundsPort;
import github.lms.lemuel.integrity.domain.HoldbackStatusReport;
import github.lms.lemuel.integrity.domain.LedgerCompletenessReport;
import github.lms.lemuel.integrity.domain.PayoutReconReport;
import github.lms.lemuel.integrity.domain.ProcessedEventCount;
import github.lms.lemuel.integrity.domain.RefundAdjustmentReport;
import github.lms.lemuel.integrity.domain.StuckStateReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Integrity Suite Phase A 조회 서비스 — grace/threshold 시각 계산만 하고
 * 집계·판정은 포트/도메인 팩토리에 위임한다.
 *
 * <p>grace window: 원장 기록·정산 확정이 비동기(ledger_outbox 폴러, confirm 배치)라
 * 방금 확정된 정산의 분개 부재는 정상이다. 폴러 주기(기본 5s)의 수백 배인 15분을
 * 기본값으로 두어 오탐을 구조적으로 차단한다 (설계서 §3.1).
 */
@Service
@Transactional(readOnly = true)
public class IntegrityQueryService implements IntegrityQueryUseCase {

    /** order 완료 환불 목록 조회 상한 — 초과 시 report.truncated 로 표시. */
    private static final int REFUND_FETCH_LIMIT = 2000;

    private final IntegrityQueryPort queryPort;
    private final LoadCompletedRefundsPort completedRefundsPort;
    private final int defaultGraceMinutes;
    private final int defaultStuckThresholdMinutes;
    /** KST 기준 시각 소스 — grace/threshold cutoff·오늘 판정이 JVM 타임존에 흔들리지 않게 한다. */
    private final Clock clock;

    public IntegrityQueryService(IntegrityQueryPort queryPort,
                                 LoadCompletedRefundsPort completedRefundsPort,
                                 @Value("${app.integrity.grace-minutes:15}") int defaultGraceMinutes,
                                 @Value("${app.integrity.stuck-threshold-minutes:60}") int defaultStuckThresholdMinutes,
                                 Clock clock) {
        this.queryPort = queryPort;
        this.completedRefundsPort = completedRefundsPort;
        this.defaultGraceMinutes = defaultGraceMinutes;
        this.defaultStuckThresholdMinutes = defaultStuckThresholdMinutes;
        this.clock = clock;
    }

    @Override
    public LedgerCompletenessReport checkLedgerCompleteness(LocalDate date, Integer graceMinutesOverride) {
        int grace = positiveOrDefault(graceMinutesOverride, defaultGraceMinutes);
        LocalDateTime graceCutoff = LocalDateTime.now(clock).minusMinutes(grace);
        return queryPort.ledgerCompleteness(date, grace, graceCutoff);
    }

    @Override
    public PayoutReconReport checkPayoutRecon(LocalDate date) {
        return queryPort.payoutRecon(date);
    }

    @Override
    public HoldbackStatusReport checkHoldbackStatus() {
        return queryPort.holdbackStatus(LocalDate.now(clock));
    }

    @Override
    public StuckStateReport checkStuckStates(Integer thresholdMinutesOverride) {
        int threshold = positiveOrDefault(thresholdMinutesOverride, defaultStuckThresholdMinutes);
        LocalDateTime cutoff = LocalDateTime.now(clock).minusMinutes(threshold);
        return queryPort.stuckStates(threshold, cutoff, LocalDate.now(clock));
    }

    @Override
    public RefundAdjustmentReport checkRefundAdjustments(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("유효한 from/to 기간이 필요합니다: " + from + " ~ " + to);
        }
        var refunds = completedRefundsPort.refundsCompleted(from, to, REFUND_FETCH_LIMIT);
        boolean truncated = refunds.size() >= REFUND_FETCH_LIMIT;

        Set<Long> adjusted = queryPort.adjustedRefundIds(
                refunds.stream().map(LoadCompletedRefundsPort.CompletedRefund::refundId).toList());

        var missing = refunds.stream().filter(r -> !adjusted.contains(r.refundId())).toList();
        BigDecimal completedTotal = refunds.stream()
                .map(LoadCompletedRefundsPort.CompletedRefund::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal missingTotal = missing.stream()
                .map(LoadCompletedRefundsPort.CompletedRefund::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return RefundAdjustmentReport.of(from, to,
                refunds.size(), completedTotal, adjusted.size(),
                missing.stream().map(LoadCompletedRefundsPort.CompletedRefund::refundId).limit(20).toList(),
                missingTotal, truncated);
    }

    @Override
    public List<ProcessedEventCount> processedEventCounts(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw new IllegalArgumentException("유효한 from/to 기간이 필요합니다: " + from + " ~ " + to);
        }
        return queryPort.processedEventCounts(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    private static int positiveOrDefault(Integer override, int def) {
        return (override != null && override > 0) ? override : def;
    }
}
