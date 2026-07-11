package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;

/**
 * 투자점수 산정 결과(순수 POJO). 3축(수익성 35 / 안정성 35 / 성장성 30) 점수와 근거 지표,
 * 총점(0~100)·등급·적격 여부를 담는다.
 */
public record InvestmentScore(
        String stockCode,
        String companyName,
        String market,
        int fiscalYear,
        int totalScore,
        InvestmentGrade grade,
        boolean investable,
        Profitability profitability,
        Stability stability,
        Growth growth
) {

    /** 수익성 축(만점 35) — 영업이익률·ROA 근거. */
    public record Profitability(int score, BigDecimal operatingMargin, BigDecimal roa) {
        public static final int MAX_SCORE = 35;
    }

    /** 안정성 축(만점 35) — 부채비율·자기자본비율 근거. */
    public record Stability(int score, BigDecimal debtRatio, BigDecimal equityRatio) {
        public static final int MAX_SCORE = 35;
    }

    /** 성장성 축(만점 30) — 매출·순이익 YoY 성장률 근거. */
    public record Growth(int score, BigDecimal revenueGrowth, BigDecimal netIncomeGrowth) {
        public static final int MAX_SCORE = 30;
    }
}
