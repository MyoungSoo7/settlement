package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;

/**
 * 한 회계연도의 요약 재무제표(순수 POJO). financial-statements-service 공개 API 응답을
 * adapter/out/external 이 이 도메인 값으로 매핑한다.
 *
 * <p>금액·파생지표는 전부 {@link BigDecimal} 이며 결측(계정과목 미제공)이면 {@code null} 이다 —
 * {@link InvestmentScorePolicy} 는 null 지표를 0점(보수적)으로 처리한다.
 * operatingMargin/netMargin/debtRatio/equityRatio/roa 는 financial 이 계산해 준 백분율(%) 값이다.
 */
public record AnnualStatement(
        int fiscalYear,
        BigDecimal revenue,
        BigDecimal operatingProfit,
        BigDecimal netIncome,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal totalEquity,
        BigDecimal operatingMargin,
        BigDecimal netMargin,
        BigDecimal debtRatio,
        BigDecimal equityRatio,
        BigDecimal roa
) {
}
