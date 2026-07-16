package github.lms.lemuel.investment.config;

import github.lms.lemuel.investment.application.port.in.ScreenRecommendationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 일일 종목 추천 스크리닝 스케줄러 — 평일 장 마감 후 1회, 유니버스를 규칙 스크리닝해 당일 세트를 생성한다.
 *
 * <p>실행 시각은 {@code app.screening.cron}(기본 18:00 KST 평일)로 조정한다. 단일 인스턴스 위성 서비스라
 * 노드 경합이 없어 {@code PartitionMaintenanceRunner} 와 같이 ShedLock 없이 안전하다(다중 replica 확장 시
 * {@code @SchedulerLock} 도입 필요). 실패는 fail-open — 다음 실행일에 다시 시도하며 이전 세트가 유지된다.
 */
@Component
public class DailyScreeningScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyScreeningScheduler.class);

    private final ScreenRecommendationsUseCase screenRecommendationsUseCase;
    private final ScreeningProperties properties;

    public DailyScreeningScheduler(ScreenRecommendationsUseCase screenRecommendationsUseCase,
                                   ScreeningProperties properties) {
        this.screenRecommendationsUseCase = screenRecommendationsUseCase;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.screening.cron}", zone = "${app.screening.zone:Asia/Seoul}")
    public void runDailyScreening() {
        LocalDate today = LocalDate.now(ZoneId.of(properties.zone()));
        try {
            int count = screenRecommendationsUseCase.screen(today);
            log.info("[screening] 일일 스크리닝 완료 — {} {}종목", today, count);
        } catch (RuntimeException e) {
            log.error("[screening] 일일 스크리닝 실패(fail-open) — {}", today, e);
        }
    }
}
