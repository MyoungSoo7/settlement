package github.lms.lemuel.investment.config;

import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase;
import github.lms.lemuel.investment.application.port.in.RunDailyScreeningUseCase.DailyScreeningReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 일일 종목 추천 스크리닝 스케줄러 — 평일 장 마감 후 1회 실행하되, <b>새 종가가 도착했을 때만</b>
 * 스크리닝한다(판정은 {@link RunDailyScreeningUseCase} 가 한다).
 *
 * <p>실행 시각은 {@code app.screening.cron}(기본 18:00 KST 평일)로 조정한다. 크론이 평일만 돌더라도
 * 공휴일 휴장은 걸러지지 않으므로, 시세 기준일이 갱신되지 않으면 스킵해 <b>거래가 없던 날짜로 추천 세트가
 * 생기지 않게</b> 한다 — 시세가 T+1 로 적재돼 "오늘이 거래일인가"는 판정할 수 없기 때문이다.
 *
 * <p>단일 인스턴스 위성 서비스라 노드 경합이 없어 {@code PartitionMaintenanceRunner} 와 같이 ShedLock 없이
 * 안전하다(다중 replica 확장 시 {@code @SchedulerLock} 도입 필요). 실패는 fail-open — 다음 실행일에 다시
 * 시도하며 이전 세트가 유지된다.
 */
@Component
public class DailyScreeningScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyScreeningScheduler.class);

    private final RunDailyScreeningUseCase runDailyScreeningUseCase;

    public DailyScreeningScheduler(RunDailyScreeningUseCase runDailyScreeningUseCase) {
        this.runDailyScreeningUseCase = runDailyScreeningUseCase;
    }

    @Scheduled(cron = "${app.screening.cron}", zone = "${app.screening.zone:Asia/Seoul}")
    public void runDailyScreening() {
        try {
            DailyScreeningReport report = runDailyScreeningUseCase.run();
            switch (report.trigger().decision()) {
                case SCREEN -> log.info("[screening] 일일 스크리닝 완료 — 시세 기준일 {} {}종목",
                        report.trigger().quoteBaseDate(), report.screenedCount());
                // 휴장일·중복 실행의 정상 경로 — 이전 세트를 그대로 유지한다.
                case SKIP_UP_TO_DATE -> log.info("[screening] 스킵 — 시세 기준일 {} 세트가 이미 최신"
                        + "(새 종가 없음: 휴장일 또는 중복 실행)", report.trigger().quoteBaseDate());
                // 이쪽은 정상이 아니다 — market 원천 장애·유니버스 미등록 의심.
                case SKIP_NO_QUOTES -> log.warn("[screening] 스킵 — 유니버스 시세를 한 건도 조회하지 못했다"
                        + "(market 원천 장애 또는 종목 미등록). 이전 추천 세트 유지");
            }
        } catch (RuntimeException e) {
            log.error("[screening] 일일 스크리닝 실패(fail-open)", e);
        }
    }
}
