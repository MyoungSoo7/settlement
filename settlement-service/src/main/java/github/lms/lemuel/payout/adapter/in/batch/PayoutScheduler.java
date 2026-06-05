package github.lms.lemuel.payout.adapter.in.batch;

import github.lms.lemuel.payout.application.port.in.ExecutePayoutUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 출금 배치.
 *
 * <p>HoldbackReleaseScheduler (03:00) 가 보류 해제를 끝낸 뒤 04:00 에 실행.
 * REQUESTED 상태 Payout 들을 일괄 펌뱅킹 호출.
 *
 * <p>한도 초과 분은 다음 영업일에 다시 시도 (skip).
 */
@Component
public class PayoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayoutScheduler.class);

    private final ExecutePayoutUseCase executeUseCase;

    public PayoutScheduler(ExecutePayoutUseCase executeUseCase) {
        this.executeUseCase = executeUseCase;
    }

    @Scheduled(cron = "${app.payout.execute-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-payout-execute", lockAtMostFor = "PT1H")
    public void execute() {
        log.info("[PayoutScheduler] 시작");
        var report = executeUseCase.executeAllPending();
        log.info("[PayoutScheduler] 완료: succeeded={}, failed={}, limited={}",
                report.succeeded(), report.failed(), report.limitedSkipped());
    }
}
