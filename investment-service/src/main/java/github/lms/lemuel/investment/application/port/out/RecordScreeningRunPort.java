package github.lms.lemuel.investment.application.port.out;

import java.time.LocalDate;

/** 일일 스크리닝 실행 기록 저장 아웃바운드 포트. */
public interface RecordScreeningRunPort {

    /**
     * 해당 시세 기준일로 스크리닝을 마쳤음을 기록한다. 같은 기준일 재실행은 멱등(갱신).
     *
     * <p>통과 종목이 0건이어도 반드시 기록해야 한다 — 그래야 다음 실행이 재스크리닝을 스킵한다.
     *
     * @param quoteBaseDate       산출 근거가 된 종가일
     * @param recommendationCount 그 실행이 만든 추천 종목 수(0 = 통과 종목 없음)
     */
    void record(LocalDate quoteBaseDate, int recommendationCount);
}
