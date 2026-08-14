package github.lms.lemuel.closing.application.service;

import github.lms.lemuel.closing.application.dto.MonthlyAggregateSnapshot;
import github.lms.lemuel.closing.application.port.in.RunMonthlyClosingUseCase;
import github.lms.lemuel.closing.application.port.out.LoadLedgerClosedPort;
import github.lms.lemuel.closing.application.port.out.LoadMonthlyAggregatePort;
import github.lms.lemuel.closing.application.port.out.LoadMonthlyClosingPort;
import github.lms.lemuel.closing.application.port.out.SaveMonthlyClosingPort;
import github.lms.lemuel.closing.domain.ClosingTotals;
import github.lms.lemuel.closing.domain.MonthlyClosingRun;
import github.lms.lemuel.closing.domain.SellerMonthlyClosing;
import github.lms.lemuel.closing.domain.exception.MonthlyClosingFailedException;
import github.lms.lemuel.closing.domain.exception.MonthlyClosingLockedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.List;

/**
 * 정보계 월마감 실행 서비스.
 *
 * <p>절차:
 * <ol>
 *   <li>run 시작(RUNNING) — 도메인이 당월·미래월 마감을 차단한다.</li>
 *   <li><b>재마감 잠금</b>: 원장 기간이 CLOSED 이고 COMPLETED 마트가 이미 있으면 거부
 *       ({@link MonthlyClosingLockedException}) — 회계 확정 후 보고 수치 변조 방지.
 *       원장 CLOSED 라도 마트가 없으면 최초 적재는 허용한다.</li>
 *   <li>DONE 정산 셀러별 집계 → 마트 행 구성(도메인 검증) → 합계 스냅샷과 함께 COMPLETED 적재.
 *       마트 교체와 run 기록은 어댑터가 한 트랜잭션으로 묶는다.</li>
 *   <li>집계·적재 실패 시 FAILED run 을 <b>별도 트랜잭션</b>으로 남기고
 *       {@link MonthlyClosingFailedException} 으로 전파한다 — 실패도 감사 대상이다.</li>
 * </ol>
 *
 * <p>이 메서드 자체는 {@code @Transactional} 이 아니다 — 성공 경로의 원자성은
 * {@code saveCompleted} 어댑터 트랜잭션이, 실패 기록은 롤백과 무관한 별도 저장이 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunMonthlyClosingService implements RunMonthlyClosingUseCase {

    private static final int FAILURE_REASON_MAX = 500;

    private final LoadLedgerClosedPort loadLedgerClosedPort;
    private final LoadMonthlyClosingPort loadClosingPort;
    private final LoadMonthlyAggregatePort loadAggregatePort;
    private final SaveMonthlyClosingPort saveClosingPort;
    private final Clock clock;

    @Override
    public MonthlyClosingRun run(YearMonth period, String triggeredBy) {
        MonthlyClosingRun run = MonthlyClosingRun.start(period, triggeredBy, YearMonth.now(clock));

        boolean completedExists =
                loadClosingPort.findRun(period).filter(MonthlyClosingRun::isCompleted).isPresent();
        if (completedExists && loadLedgerClosedPort.isLedgerClosed(period)) {
            throw new MonthlyClosingLockedException(period);
        }

        try {
            MonthlyAggregateSnapshot snapshot = loadAggregatePort.load(period);
            List<SellerMonthlyClosing> rows = snapshot.rows().stream()
                    .map(r -> SellerMonthlyClosing.of(period, r.sellerId(), r.settlementCount(),
                            r.grossAmount(), r.refundedAmount(), r.commissionAmount(),
                            r.holdbackAmount(), r.netAmount()))
                    .toList();
            long settlementCount = rows.stream().mapToLong(SellerMonthlyClosing::getSettlementCount).sum();

            run.complete(rows.size(), settlementCount, snapshot.unmappedCount(), snapshot.pendingCount(),
                    ClosingTotals.sumOf(rows));
            MonthlyClosingRun saved = saveClosingPort.saveCompleted(run, rows);

            log.info("[MonthlyClosing] COMPLETED: period={}, sellers={}, settlements={}, unmapped={}, pending={}, net={}",
                    period, saved.getSellerCount(), saved.getSettlementCount(),
                    saved.getUnmappedCount(), saved.getPendingCount(), saved.getTotals().netAmount());
            return saved;
        } catch (RuntimeException e) {
            run.fail(truncate(e.getMessage() != null ? e.getMessage() : e.toString()));
            saveClosingPort.saveRun(run);
            log.error("[MonthlyClosing] FAILED: period={}, reason={}", period, run.getFailureReason(), e);
            throw new MonthlyClosingFailedException(period, e);
        }
    }

    private static String truncate(String reason) {
        return reason.length() <= FAILURE_REASON_MAX ? reason : reason.substring(0, FAILURE_REASON_MAX);
    }
}
