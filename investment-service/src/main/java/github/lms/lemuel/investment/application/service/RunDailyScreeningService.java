package github.lms.lemuel.investment.application.service;

import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase;
import github.lms.lemuel.investment.application.port.in.ScreenRecommendationsUseCase;
import github.lms.lemuel.investment.application.port.out.LoadDailyClosesPort;
import github.lms.lemuel.investment.application.port.out.LoadScreeningRunPort;
import github.lms.lemuel.investment.application.port.out.RecordScreeningRunPort;
import github.lms.lemuel.investment.config.ScreeningProperties;
import github.lms.lemuel.investment.domain.DailyClose;
import github.lms.lemuel.investment.domain.ScreeningTrigger;
import github.lms.lemuel.investment.domain.ScreeningTriggerPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 크론 경로의 일일 스크리닝 실행 — <b>새 종가가 도착했을 때만</b> 스크리닝한다.
 *
 * <p>유니버스 종가를 모아 {@link ScreeningTriggerPolicy} 로 판정하고, SCREEN 이면 시세 기준일을
 * 추천일로 삼아 {@link ScreenRecommendationsUseCase} 에 위임한다. 휴장일에는 새 종가가 생기지 않아
 * 다음 실행이 스킵되므로, 거래가 없던 날짜로 추천 세트가 만들어지지 않는다.
 *
 * <p>종가 조회는 {@link ScreenRecommendationsUseCase} 가 곧이어 쓰는 것과 같은 원천·같은 캐시(10분)라
 * 판정 때문에 원천 호출이 늘지 않는다. 개별 종목 조회 실패는 그 종목만 건너뛴다(부분 실패 ≠ 전체 실패).
 */
@Service
public class RunDailyScreeningService implements RunDailyScreeningUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunDailyScreeningService.class);

    private final ScreenRecommendationsUseCase screenRecommendationsUseCase;
    private final LoadDailyClosesPort loadDailyClosesPort;
    private final LoadScreeningRunPort loadScreeningRunPort;
    private final RecordScreeningRunPort recordScreeningRunPort;
    private final ScreeningProperties properties;
    private final ScreeningTriggerPolicy policy = new ScreeningTriggerPolicy();

    public RunDailyScreeningService(ScreenRecommendationsUseCase screenRecommendationsUseCase,
                                    LoadDailyClosesPort loadDailyClosesPort,
                                    LoadScreeningRunPort loadScreeningRunPort,
                                    RecordScreeningRunPort recordScreeningRunPort,
                                    ScreeningProperties properties) {
        this.screenRecommendationsUseCase = screenRecommendationsUseCase;
        this.loadDailyClosesPort = loadDailyClosesPort;
        this.loadScreeningRunPort = loadScreeningRunPort;
        this.recordScreeningRunPort = recordScreeningRunPort;
        this.properties = properties;
    }

    /**
     * 판정 기준은 <b>추천 산출물이 아니라 실행 기록</b>이다 — 통과 종목 0건이면 추천 행이 남지 않아
     * 산출물로는 "이미 돌았음"을 표현할 수 없고, 같은 기준일을 매일 재스크리닝하게 된다.
     */
    @Override
    public DailyScreeningReport run() {
        ScreeningTrigger trigger = policy.decide(
                universeCloses(),
                loadScreeningRunPort.loadLatestScreenedDate().orElse(null));
        if (!trigger.shouldScreen()) {
            return DailyScreeningReport.skipped(trigger);
        }
        int screenedCount = screenRecommendationsUseCase.screen(trigger.quoteBaseDate());
        // 통과 종목이 0건이어도 기록한다 — 이 한 줄이 빠지면 빈 세트인 날의 기준일이 사라져
        // 다음 실행이 같은 기준일을 다시 스크리닝한다(휴장일 스킵 무력화).
        recordScreeningRunPort.record(trigger.quoteBaseDate(), screenedCount);
        return DailyScreeningReport.screened(trigger, screenedCount);
    }

    /** 유니버스 전 종목의 종가를 모은다 — 조회 실패 종목은 제외하고 나머지로 기준일을 정한다. */
    private List<DailyClose> universeCloses() {
        List<DailyClose> closes = new ArrayList<>();
        for (ScreeningProperties.UniverseEntry entry : properties.universe()) {
            try {
                closes.addAll(loadDailyClosesPort.loadRecentYear(entry.code()));
            } catch (RuntimeException e) {
                log.warn("[screening] {} 시세 조회 실패 — 기준일 판정에서 제외: {}", entry.code(), e.getMessage());
            }
        }
        return closes;
    }
}
