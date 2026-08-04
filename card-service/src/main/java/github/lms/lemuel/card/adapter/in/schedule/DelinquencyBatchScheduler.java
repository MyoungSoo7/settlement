package github.lms.lemuel.card.adapter.in.schedule;

import github.lms.lemuel.card.application.port.in.MarkDelinquentStatementsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 연체 배치 스케줄러 — 일 1회 새벽 02:00 KST.
 *
 * <p>만기일 경과 + 미납 명세서를 DELINQUENT 로 전환하고, 해당 카드계정을 DELINQUENT 로 전이시킨다.
 * 연체 카드계정에서는 모든 카드 승인이 {@code CARD_SUSPENDED} 로 거절된다.
 *
 * <p>{@link SchedulerLock} 으로 다중 인스턴스 중복 실행을 방지한다.
 * 1건 실패는 삼키고 나머지를 계속 처리(fail-open) — 서비스에 위임된다.
 */
@Component
public class DelinquencyBatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(DelinquencyBatchScheduler.class);

    private final MarkDelinquentStatementsUseCase markDelinquentStatementsUseCase;

    public DelinquencyBatchScheduler(MarkDelinquentStatementsUseCase markDelinquentStatementsUseCase) {
        this.markDelinquentStatementsUseCase = markDelinquentStatementsUseCase;
    }

    /**
     * 일 1회 새벽 02:00 KST 연체 대상 명세서 전이.
     * {@code lockAtMostFor=PT30M}: 인스턴스 비정상 종료 시 락 최대 보유 시간.
     */
    @Scheduled(cron = "${app.card.delinquency.cron:0 0 2 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "card-delinquency-batch", lockAtMostFor = "PT30M")
    public void markDelinquent() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        log.info("[DelinquencyBatch] 시작 (today={})", today);
        try {
            int count = markDelinquentStatementsUseCase.markDelinquent(today);
            log.info("[DelinquencyBatch] 완료: 연체 처리 {}건", count);
        } catch (RuntimeException e) {
            log.error("[DelinquencyBatch] 배치 실패(fail-open) — 다음 실행에서 재시도", e);
        }
    }
}
