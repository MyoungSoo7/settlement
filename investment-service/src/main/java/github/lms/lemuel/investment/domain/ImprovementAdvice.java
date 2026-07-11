package github.lms.lemuel.investment.domain;

/**
 * 투자받기 개선 포인트 1건 — 어느 축의 어떤 지표를 다음 구간까지 개선하면 몇 점이 오르는지.
 * 예측·권유가 아니라 {@link InvestmentScorePolicy} 구간표에서 결정적으로 유도되는 사실 서술이다.
 */
public record ImprovementAdvice(Axis axis, String metric, String message, int potentialGain) {

    public enum Axis {
        /** 수익성 (영업이익률·ROA). */
        PROFITABILITY,
        /** 안정성 (부채비율·자기자본비율). */
        STABILITY,
        /** 성장성 (매출·순이익 YoY). */
        GROWTH
    }
}
