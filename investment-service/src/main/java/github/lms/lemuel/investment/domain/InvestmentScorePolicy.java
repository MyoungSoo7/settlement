package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 코스피/코스닥 회계자료 기반 투자점수 산정기 — 순수 도메인(프레임워크 의존 0).
 *
 * <p>최신 회계연도 재무제표(+직전 연도)로 3축을 구간 매핑해 0~100 정수 총점을 낸다.
 * <ul>
 *   <li><b>수익성 35</b> = 영업이익률 구간(≤20) + ROA 구간(≤15)</li>
 *   <li><b>안정성 35</b> = 부채비율 구간(≤20, 낮을수록 고득점) + 자기자본비율 구간(≤15)</li>
 *   <li><b>성장성 30</b> = 매출 YoY 구간(≤15) + 순이익 YoY 구간(≤15). 직전 연도 없으면 중립 50%(=15)</li>
 * </ul>
 *
 * <p>모든 구간 경계는 결정적({@link BigDecimal#compareTo} 기준)이다. null 지표(계정과목 미제공,
 * 또는 성장률 산정 불가)는 해당 지표 0점으로 보수적으로 처리한다.
 */
public class InvestmentScorePolicy {

    private static final int INVESTABLE_THRESHOLD = 60;

    /** {@link CompanyFinancials} 로 투자점수를 산정한다. 재무제표가 없으면 예외(호출측이 사전 검증). */
    public InvestmentScore score(CompanyFinancials financials) {
        if (financials == null || !financials.hasStatements()) {
            throw new IllegalArgumentException("재무제표가 없어 투자점수를 산정할 수 없습니다");
        }
        AnnualStatement latest = financials.latest();
        AnnualStatement previous = financials.previous();

        InvestmentScore.Profitability profitability = profitability(latest);
        InvestmentScore.Stability stability = stability(latest);
        InvestmentScore.Growth growth = growth(latest, previous);

        int total = profitability.score() + stability.score() + growth.score();
        InvestmentGrade grade = InvestmentGrade.fromScore(total);

        return new InvestmentScore(
                financials.stockCode(),
                financials.companyName(),
                financials.market(),
                latest.fiscalYear(),
                total,
                grade,
                total >= INVESTABLE_THRESHOLD,
                profitability,
                stability,
                growth);
    }

    // ─── 수익성 (35) ──────────────────────────────────────────────────────────────

    private InvestmentScore.Profitability profitability(AnnualStatement s) {
        int score = operatingMarginBand(s.operatingMargin()) + roaBand(s.roa());
        return new InvestmentScore.Profitability(score, s.operatingMargin(), s.roa());
    }

    /** 영업이익률(%) 구간 — 최대 20. */
    private int operatingMarginBand(BigDecimal margin) {
        if (margin == null) {
            return 0;
        }
        if (gte(margin, "20")) {
            return 20;
        }
        if (gte(margin, "15")) {
            return 16;
        }
        if (gte(margin, "10")) {
            return 12;
        }
        if (gte(margin, "5")) {
            return 8;
        }
        if (gte(margin, "0")) {
            return 4;
        }
        return 0;
    }

    /** ROA(%) 구간 — 최대 15. */
    private int roaBand(BigDecimal roa) {
        if (roa == null) {
            return 0;
        }
        if (gte(roa, "15")) {
            return 15;
        }
        if (gte(roa, "10")) {
            return 12;
        }
        if (gte(roa, "7")) {
            return 9;
        }
        if (gte(roa, "4")) {
            return 6;
        }
        if (gte(roa, "0")) {
            return 3;
        }
        return 0;
    }

    // ─── 안정성 (35) ──────────────────────────────────────────────────────────────

    private InvestmentScore.Stability stability(AnnualStatement s) {
        int score = debtRatioBand(s.debtRatio()) + equityRatioBand(s.equityRatio());
        return new InvestmentScore.Stability(score, s.debtRatio(), s.equityRatio());
    }

    /** 부채비율(%) 구간 — 낮을수록 고득점, 최대 20. */
    private int debtRatioBand(BigDecimal debtRatio) {
        if (debtRatio == null) {
            return 0;
        }
        if (lte(debtRatio, "50")) {
            return 20;
        }
        if (lte(debtRatio, "100")) {
            return 16;
        }
        if (lte(debtRatio, "150")) {
            return 12;
        }
        if (lte(debtRatio, "200")) {
            return 8;
        }
        if (lte(debtRatio, "300")) {
            return 4;
        }
        return 0;
    }

    /** 자기자본비율(%) 구간 — 높을수록 고득점, 최대 15. */
    private int equityRatioBand(BigDecimal equityRatio) {
        if (equityRatio == null) {
            return 0;
        }
        if (gte(equityRatio, "60")) {
            return 15;
        }
        if (gte(equityRatio, "50")) {
            return 12;
        }
        if (gte(equityRatio, "40")) {
            return 9;
        }
        if (gte(equityRatio, "30")) {
            return 6;
        }
        if (gte(equityRatio, "20")) {
            return 3;
        }
        return 0;
    }

    // ─── 성장성 (30) ──────────────────────────────────────────────────────────────

    private InvestmentScore.Growth growth(AnnualStatement latest, AnnualStatement previous) {
        if (previous == null) {
            // 직전 연도 부재 → 성장성을 판단할 수 없으므로 중립 50%(=15) 부여, 근거 지표는 null.
            return new InvestmentScore.Growth(InvestmentScore.Growth.MAX_SCORE / 2, null, null);
        }
        BigDecimal revenueGrowth = yoyGrowth(latest.revenue(), previous.revenue());
        BigDecimal netIncomeGrowth = yoyGrowth(latest.netIncome(), previous.netIncome());
        int score = growthBand(revenueGrowth) + growthBand(netIncomeGrowth);
        return new InvestmentScore.Growth(score, revenueGrowth, netIncomeGrowth);
    }

    /**
     * YoY 성장률(%) = (당기 − 전기) / 전기 × 100. 당기/전기 결측이거나 전기 ≤ 0(분모 부적격)이면
     * null 을 반환해 해당 지표를 0점 처리한다(보수적).
     */
    private BigDecimal yoyGrowth(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() <= 0) {
            return null;
        }
        return current.subtract(previous)
                .divide(previous, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));
    }

    /** YoY 성장률(%) 구간 — 각 지표 최대 15. */
    private int growthBand(BigDecimal growth) {
        if (growth == null) {
            return 0;
        }
        if (gte(growth, "20")) {
            return 15;
        }
        if (gte(growth, "10")) {
            return 12;
        }
        if (gte(growth, "5")) {
            return 9;
        }
        if (gte(growth, "0")) {
            return 6;
        }
        if (gte(growth, "-10")) {
            return 3;
        }
        return 0;
    }

    // ─── 비교 헬퍼 ────────────────────────────────────────────────────────────────

    private static boolean gte(BigDecimal value, String threshold) {
        return value.compareTo(new BigDecimal(threshold)) >= 0;
    }

    private static boolean lte(BigDecimal value, String threshold) {
        return value.compareTo(new BigDecimal(threshold)) <= 0;
    }
}
