package github.lms.lemuel.investment.application.port.in;

import github.lms.lemuel.investment.domain.ScreeningTrigger;

/**
 * 크론 경로의 일일 스크리닝 실행 — 시세 기준일을 스스로 판정하고, 새 종가가 없으면 건너뛴다.
 *
 * <p>날짜를 인자로 받는 {@link ScreenRecommendationsUseCase#screen(java.time.LocalDate)} 와 역할이 다르다:
 * 그쪽은 "이 날짜로 무조건 스크리닝"(운영 수동 트리거), 이쪽은 "돌 필요가 있는지부터 판단"(스케줄러)이다.
 */
public interface RunDailyScreeningUseCase {

    DailyScreeningReport run();

    /** 실행 결과 — 판정과, 실제로 돌았다면 저장된 종목 수(스킵이면 0). */
    record DailyScreeningReport(ScreeningTrigger trigger, int screenedCount) {

        public static DailyScreeningReport skipped(ScreeningTrigger trigger) {
            return new DailyScreeningReport(trigger, 0);
        }

        public static DailyScreeningReport screened(ScreeningTrigger trigger, int screenedCount) {
            return new DailyScreeningReport(trigger, screenedCount);
        }
    }
}
