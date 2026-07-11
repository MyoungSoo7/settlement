package github.lms.lemuel.investment.adapter.in.web.dto;

import github.lms.lemuel.investment.domain.ImprovementAdvicePolicy;
import github.lms.lemuel.investment.domain.InvestmentScore;

import java.math.BigDecimal;
import java.util.List;

/**
 * 투자점수 응답 — 총점·등급·적격 + 3축(수익성/안정성/성장성) 점수·근거 지표
 * + 투자받기 개선 포인트(구간표 기반 결정적 유도 — 전 지표 최상 구간이면 빈 배열).
 */
public record InvestmentScoreResponse(
        String stockCode,
        String companyName,
        String market,
        int fiscalYear,
        int totalScore,
        String grade,
        boolean investable,
        Profitability profitability,
        Stability stability,
        Growth growth,
        List<Improvement> improvements) {

    private static final ImprovementAdvicePolicy ADVICE_POLICY = new ImprovementAdvicePolicy();

    public record Profitability(int score, int maxScore, BigDecimal operatingMargin, BigDecimal roa) {
    }

    public record Stability(int score, int maxScore, BigDecimal debtRatio, BigDecimal equityRatio) {
    }

    public record Growth(int score, int maxScore, BigDecimal revenueGrowth, BigDecimal netIncomeGrowth) {
    }

    /** 개선 포인트 1건 — axis 는 PROFITABILITY/STABILITY/GROWTH. */
    public record Improvement(String axis, String metric, String message, int potentialGain) {
    }

    public static InvestmentScoreResponse from(InvestmentScore s) {
        return new InvestmentScoreResponse(
                s.stockCode(),
                s.companyName(),
                s.market(),
                s.fiscalYear(),
                s.totalScore(),
                s.grade().name(),
                s.investable(),
                new Profitability(s.profitability().score(), InvestmentScore.Profitability.MAX_SCORE,
                        s.profitability().operatingMargin(), s.profitability().roa()),
                new Stability(s.stability().score(), InvestmentScore.Stability.MAX_SCORE,
                        s.stability().debtRatio(), s.stability().equityRatio()),
                new Growth(s.growth().score(), InvestmentScore.Growth.MAX_SCORE,
                        s.growth().revenueGrowth(), s.growth().netIncomeGrowth()),
                ADVICE_POLICY.advise(s).stream()
                        .map(a -> new Improvement(a.axis().name(), a.metric(), a.message(), a.potentialGain()))
                        .toList());
    }
}
