package github.lms.lemuel.insurance.adapter.in.schedule;

import github.lms.lemuel.insurance.application.port.in.ExpireProposalsUseCase;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 매일 새벽 가입설계 만기 스윕 배치 — 유효기간(30일) 경과 QUOTED 설계를 EXPIRED 로 전이한다.
 *
 * <p>금전 사건이 아니므로 건 단위 감사는 없다(설계는 청약 전 단계). 전이는 반드시 도메인
 * 메서드({@code expire})를 통과한다 — 스캔 술어와 도메인 가드가 어긋나면 예외로 드러난다.
 */
@Component
public class ProposalExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProposalExpiryScheduler.class);

    private final ExpireProposalsUseCase useCase;
    /** KST 기준 시각 소스 — cron 은 KST 인데 본문 now() 가 UTC 면 만기 판정이 하루 어긋난다. */
    private final Clock clock;

    public ProposalExpiryScheduler(ExpireProposalsUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    /** 매일 01:30 (KST) — 계약 만기 배치(02:00)보다 앞선 한산한 슬롯. */
    @Scheduled(cron = "${app.insurance.batch.proposal-expiry-cron:0 30 1 * * *}", zone = "Asia/Seoul")
    @SchedulerLock(name = "insurance-proposal-expiry", lockAtMostFor = "PT15M")
    public void expireDue() {
        LocalDate today = LocalDate.now(clock);
        int expired = useCase.expireOn(today);
        log.info("[ProposalExpiry] 완료: today={} expired={}", today, expired);
    }
}
