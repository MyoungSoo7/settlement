package github.lms.lemuel.investment.domain;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;

import java.time.LocalDate;

/**
 * 일일 스크리닝 실행 판정 결과 — 실행할지, 건너뛴다면 왜인지.
 *
 * <p>{@link Decision#SCREEN} 일 때만 {@link #quoteBaseDate()} 가 존재하며, 그 날짜가 곧 추천일이다
 * (추천 세트는 그날 종가로 산출된 것이므로 실행일이 아니라 시세 기준일에 귀속된다).
 */
public record ScreeningTrigger(Decision decision, LocalDate quoteBaseDate) {

    /** 실행 판정 — 스크리닝 / 시세 없음 스킵 / 이미 최신 스킵. */
    public enum Decision {
        /** 새 종가가 도착했다 — 그 기준일로 스크리닝한다. */
        SCREEN,
        /** 시세를 한 건도 못 구했다(원천 장애·미등록) — 판단 근거가 없어 이전 세트를 유지한다. */
        SKIP_NO_QUOTES,
        /** 최신 종가일 세트가 이미 저장돼 있다(휴장일·중복 실행) — 재스크리닝할 이유가 없다. */
        SKIP_UP_TO_DATE
    }

    public ScreeningTrigger {
        if (decision == null) {
            throw new InvestmentInvariantViolationException("decision 은 필수입니다.");
        }
        if (decision == Decision.SCREEN && quoteBaseDate == null) {
            throw new InvestmentInvariantViolationException("SCREEN 판정에는 시세 기준일이 필요합니다.");
        }
    }

    public static ScreeningTrigger screen(LocalDate quoteBaseDate) {
        return new ScreeningTrigger(Decision.SCREEN, quoteBaseDate);
    }

    public static ScreeningTrigger skipNoQuotes() {
        return new ScreeningTrigger(Decision.SKIP_NO_QUOTES, null);
    }

    /** 이미 최신 — 스킵 사유를 로그로 남길 수 있게 기준일을 함께 담는다. */
    public static ScreeningTrigger skipUpToDate(LocalDate quoteBaseDate) {
        return new ScreeningTrigger(Decision.SKIP_UP_TO_DATE, quoteBaseDate);
    }

    public boolean shouldScreen() {
        return decision == Decision.SCREEN;
    }
}
