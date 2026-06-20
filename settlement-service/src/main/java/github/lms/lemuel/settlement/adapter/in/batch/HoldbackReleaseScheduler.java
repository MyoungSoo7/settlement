package github.lms.lemuel.settlement.adapter.in.batch;

import github.lms.lemuel.settlement.application.port.in.ReleaseHoldbackUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 매일 새벽 보류 해제 배치.
 *
 * <p>release_date 도달한 정산의 holdback 을 일괄 해제하여 셀러가 출금 가능하게 한다.
 * 배치 시간은 정산 사이클 / 출금 처리 시간보다 앞서 둬야 같은 날 출금에 반영됨.
 */
@Component
public class HoldbackReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldbackReleaseScheduler.class);

    private final ReleaseHoldbackUseCase useCase;

    public HoldbackReleaseScheduler(ReleaseHoldbackUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 매일 03:00 (KST) 실행. 운영 환경 timezone 설정 확인 필요.
     */
    @Scheduled(cron = "${app.holdback.release-cron:0 0 3 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-holdback-release", lockAtMostFor = "PT30M")
    public void releaseDue() {
        LocalDate today = LocalDate.now();
        log.info("[HoldbackRelease] 시작: today={}", today);
        int released = useCase.releaseAllDueOn(today);
        log.info("[HoldbackRelease] 완료: 해제 건수={}", released);
    }
}
