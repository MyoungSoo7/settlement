package github.lms.lemuel.financial.adapter.in.schedule;

import github.lms.lemuel.financial.adapter.in.web.SyncStatusTracker;
import github.lms.lemuel.financial.application.port.in.SyncCompaniesUseCase;
import github.lms.lemuel.financial.application.port.in.SyncResult;
import github.lms.lemuel.financial.application.port.in.SyncStatementsUseCase;
import github.lms.lemuel.financial.application.port.out.DartClientPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Year;
import java.util.function.Supplier;

/**
 * DART 재무제표·기업 자동 수집 스케줄러.
 *
 * <p>재무제표는 연 1회(사업보고서 ~3~4월) 갱신되는 저빈도 데이터라, 시세처럼 매일 돌 필요가 없다.
 * <ul>
 *   <li>재무제표: 매주({@code statements-cron}, 기본 일 04:00 KST) 최근 {@code recent-years}개 연도
 *       (당해·전년)를 재수집 — 신규 사업보고서/정정공시를 반영한다. 과거 확정 연도는 안 바뀌므로 제외.</li>
 *   <li>기업 목록: 매월({@code companies-cron}, 기본 1일 05:00 KST) 재수집 — 신규 상장/상장폐지 반영.</li>
 * </ul>
 *
 * <p>{@code DART_API_KEY} 미설정이면 조용히 skip(예외 아님). 수동 트리거와 {@link SyncStatusTracker}를
 * 공유해 동시 실행을 방지하고, 실패는 로그+트래커 FAILED 로만 남기고 삼킨다(다음 스케줄 재시도).
 */
@Component
public class FinancialSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncScheduler.class);

    private final SyncStatementsUseCase syncStatementsUseCase;
    private final SyncCompaniesUseCase syncCompaniesUseCase;
    private final DartClientPort dartClient;
    private final SyncStatusTracker tracker;
    private final int recentYears;
    private final Clock clock;

    public FinancialSyncScheduler(SyncStatementsUseCase syncStatementsUseCase,
                                  SyncCompaniesUseCase syncCompaniesUseCase,
                                  DartClientPort dartClient,
                                  SyncStatusTracker tracker,
                                  @Value("${app.financial.sync.schedule.recent-years:2}") int recentYears,
                                  Clock clock) {
        this.syncStatementsUseCase = syncStatementsUseCase;
        this.syncCompaniesUseCase = syncCompaniesUseCase;
        this.dartClient = dartClient;
        this.tracker = tracker;
        this.recentYears = recentYears;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.financial.sync.schedule.statements-cron:0 0 4 * * SUN}",
            zone = "${app.financial.sync.schedule.zone:Asia/Seoul}")
    public void runStatementsSync() {
        if (!dartClient.isConfigured()) {
            log.info("DART_API_KEY 미설정 — 주간 자동 재무제표 수집 skip");
            return;
        }
        int currentYear = Year.now(clock).getValue();
        for (int i = 0; i < recentYears; i++) {
            int year = currentYear - i;
            if (!syncOne("statements-" + year, () -> syncStatementsUseCase.syncStatements(year))) {
                return;   // 동시 실행 중이면 이번 회차 전체 중단
            }
        }
    }

    @Scheduled(cron = "${app.financial.sync.schedule.companies-cron:0 0 5 1 * *}",
            zone = "${app.financial.sync.schedule.zone:Asia/Seoul}")
    public void runCompaniesSync() {
        if (!dartClient.isConfigured()) {
            log.info("DART_API_KEY 미설정 — 월간 자동 기업 목록 수집 skip");
            return;
        }
        syncOne("companies", syncCompaniesUseCase::syncCompanies);
    }

    /** @return false 면 다른 동기화가 실행 중이라 중단해야 함 */
    private boolean syncOne(String job, Supplier<SyncResult> task) {
        if (!tracker.tryStart(job)) {
            log.warn("동기화가 이미 실행 중이라 자동 수집 skip (진행중={})", tracker.current().job());
            return false;
        }
        try {
            SyncResult result = task.get();
            tracker.complete(result);
            log.info("DART 자동 수집 완료 job={} 결과={}", job, result);
        } catch (RuntimeException e) {
            tracker.fail(e.getMessage() != null ? e.getMessage() : e.toString());
            log.error("DART 자동 수집 실패 job={}", job, e);
        }
        return true;
    }
}
