package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 경제지표 최신값 스냅샷 — economics-service 공개 API 의 도메인 표현(기준금리·국고채3년·환율·CPI 등). */
public record EconomicIndicatorSnapshot(String code, String name, String unit,
                                        BigDecimal value, LocalDate observedDate,
                                        BigDecimal changeAmount) {
}
