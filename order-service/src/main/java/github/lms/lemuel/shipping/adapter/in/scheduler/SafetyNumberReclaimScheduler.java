package github.lms.lemuel.shipping.adapter.in.scheduler;

import github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 만료된 안심번호 회수 스케줄러.
 *
 * <p>회수가 멈추면 풀이 말라 신규 주문이 실번호로 나간다 — 조용히 개인정보 노출이 늘어나는 형태의
 * 고장이라, 실패를 삼키지 않고 로그로 남긴다(다음 주기가 다시 시도한다).
 */
@Component
public class SafetyNumberReclaimScheduler {

    private static final Logger log = LoggerFactory.getLogger(SafetyNumberReclaimScheduler.class);

    private final SafetyNumberUseCase useCase;
    private final int batchSize;

    public SafetyNumberReclaimScheduler(SafetyNumberUseCase useCase,
                                        @Value("${app.shipping.safety-number.reclaim-batch:200}") int batchSize) {
        this.useCase = useCase;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.shipping.safety-number.reclaim-cron:0 10 * * * *}")
    public void reclaim() {
        try {
            int released = useCase.releaseExpired(OffsetDateTime.now(), batchSize);
            if (released > 0) {
                log.info("안심번호 만료 회수 완료: {} 건", released);
            }
        } catch (RuntimeException e) {
            log.error("안심번호 회수 실패 — 풀 고갈 시 실번호가 노출된다", e);
        }
    }
}
