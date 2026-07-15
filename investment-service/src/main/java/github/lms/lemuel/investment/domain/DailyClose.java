package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 일별 종가 1점 — market-service 시세 시계열의 도메인 표현. */
public record DailyClose(LocalDate date, BigDecimal close) {

    /** 직전일 대비 상승했는가 — 종가가 이전 점보다 크면 참(보합·하락은 거짓). 연속 상승 판정의 단위. */
    public boolean roseFrom(DailyClose previous) {
        return close.compareTo(previous.close) > 0;
    }

    /** 이 종가가 주어진 기준일 이후(당일 포함)인가 — 52주 창 등 기간 필터의 단위. */
    public boolean onOrAfter(LocalDate from) {
        return !date.isBefore(from);
    }
}
