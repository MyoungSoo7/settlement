package github.lms.lemuel.loan.application.service;

import github.lms.lemuel.loan.application.port.in.ManageLoanCollectionUseCase;
import github.lms.lemuel.loan.application.port.out.LoadLoanPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 선정산 대출 자동 회수 배치 — 만기(dueAt) 경과분을 연체(OVERDUE)로, 상각 임계 경과분을 상각(WRITTEN_OFF)으로
 * 승격시킨다. 수동 회수(LoanController 의 ADMIN ops)와 병행하는 시간 기반 자동화.
 *
 * <p>단일 인스턴스 위성 서비스라 노드 경합이 없어 ShedLock 없이 안전하다(다중 replica 로 확장 시
 * {@code PartitionMaintenanceRunner} 주석대로 {@code @SchedulerLock} 도입 필요). 시각은 KST {@link Clock}
 * 기준(TimeConfig) — 자정~09시 경계에서 날짜가 어긋나지 않는다.
 *
 * <p><b>fail-open 아님(건별 격리):</b> 연체·상각은 금전 상태전이라 실패를 통째로 삼키지 않는다. 대신
 * 건별 try/catch 로 한 건 실패가 배치 전체를 멈추지 않게 격리하고(예: 스캔~처리 사이 상태가 바뀌어 전이 불가),
 * 경고 로그로 남긴다. 전이·불변식·전표·감사는 {@link ManageLoanCollectionUseCase} 가 건별 트랜잭션으로 강제한다.
 */
@Component
public class LoanOverdueScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoanOverdueScheduler.class);

    private final ManageLoanCollectionUseCase collectionUseCase;
    private final LoadLoanPort loadLoanPort;
    private final Clock clock;
    private final int graceDays;
    private final int writeOffDays;

    public LoanOverdueScheduler(ManageLoanCollectionUseCase collectionUseCase,
                                LoadLoanPort loadLoanPort,
                                Clock clock,
                                @Value("${app.loan.overdue.grace-days:0}") int graceDays,
                                @Value("${app.loan.overdue.write-off-days:30}") int writeOffDays) {
        this.collectionUseCase = collectionUseCase;
        this.loadLoanPort = loadLoanPort;
        this.clock = clock;
        this.graceDays = graceDays;
        this.writeOffDays = writeOffDays;
    }

    /** 매일 03:00 KST — 만기 경과 연체 승격 + 상각 임계 경과 상각. */
    @Scheduled(cron = "${app.loan.overdue.scan-cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void scan() {
        int overdue = promoteOverdue();
        int written = promoteWriteOff();
        log.info("[LoanOverdueScan] 완료 — 연체 승격 {}건, 상각 {}건 (graceDays={}, writeOffDays={})",
                overdue, written, graceDays, writeOffDays);
    }

    /** 만기(+grace) 경과한 DISBURSED 대출을 OVERDUE 로 승격. 처리 건수 반환. */
    int promoteOverdue() {
        LocalDateTime asOf = LocalDateTime.now(clock).minusDays(graceDays);
        List<LoanAdvance> candidates = loadLoanPort.findOverdueCandidates(asOf);
        int done = 0;
        for (LoanAdvance loan : candidates) {
            try {
                collectionUseCase.markOverdue(loan.getId());
                done++;
            } catch (RuntimeException e) {
                log.warn("[LoanOverdueScan] 연체 승격 실패 — 스킵. loanId={}, 사유={}", loan.getId(), e.getMessage());
            }
        }
        return done;
    }

    /** 만기 후 writeOffDays 경과한 OVERDUE 대출을 WRITTEN_OFF 로 상각(대손 전표). 처리 건수 반환. */
    int promoteWriteOff() {
        LocalDateTime asOf = LocalDateTime.now(clock).minusDays(writeOffDays);
        List<LoanAdvance> candidates = loadLoanPort.findWriteOffCandidates(asOf);
        int done = 0;
        for (LoanAdvance loan : candidates) {
            try {
                collectionUseCase.writeOff(loan.getId());
                done++;
            } catch (RuntimeException e) {
                log.warn("[LoanOverdueScan] 상각 실패 — 스킵. loanId={}, 사유={}", loan.getId(), e.getMessage());
            }
        }
        return done;
    }
}
