package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 일별 종가 1점 — market-service 시세 시계열의 도메인 표현. */
public record DailyClose(LocalDate date, BigDecimal close) {
}
