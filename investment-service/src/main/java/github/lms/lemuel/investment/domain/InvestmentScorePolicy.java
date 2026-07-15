package github.lms.lemuel.investment.domain;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

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
            throw new InvestmentInvariantViolationException("재무제표가 없어 투자점수를 산정할 수 없습니다");
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

    // ─── 구간 테이블 (선언적 밴드) ─────────────────────────────────────────────────
    //
    // 각 지표의 점수는 임계값 구간 매핑이다. if/else 사슬 대신 (경계값, 점수) 밴드 테이블 +
    // 단일 조회 메서드({@link #bandScore})로 표현해, 정책 변경을 데이터 수정으로 국한한다.
    // 매칭 방향은 {@link Match} 로 지정한다: 높을수록 고득점 지표는 AT_LEAST(경계값 이상, 내림차순),
    // 부채비율처럼 낮을수록 고득점인 지표는 AT_MOST(경계값 이하, 오름차순). 미매칭·null → 0점.

    /** 영업이익률(%) — 최대 20, 경계값 이상 매칭(내림차순). 음수/null → 0. */
    private static final List<Band> OPERATING_MARGIN_BANDS = List.of(
            new Band("20", 20), new Band("15", 16), new Band("10", 12), new Band("5", 8), new Band("0", 4));

    /** ROA(%) — 최대 15, 경계값 이상 매칭(내림차순). 음수/null → 0. */
    private static final List<Band> ROA_BANDS = List.of(
            new Band("15", 15), new Band("10", 12), new Band("7", 9), new Band("4", 6), new Band("0", 3));

    /** 부채비율(%) — 최대 20, 경계값 이하 매칭(오름차순, 낮을수록 고득점). >300/null → 0. */
    private static final List<Band> DEBT_RATIO_BANDS = List.of(
            new Band("50", 20), new Band("100", 16), new Band("150", 12), new Band("200", 8), new Band("300", 4));

    /** 자기자본비율(%) — 최대 15, 경계값 이상 매칭(내림차순, 높을수록 고득점). <20/null → 0. */
    private static final List<Band> EQUITY_RATIO_BANDS = List.of(
            new Band("60", 15), new Band("50", 12), new Band("40", 9), new Band("30", 6), new Band("20", 3));

    /** YoY 성장률(%) — 지표당 최대 15, 경계값 이상 매칭(내림차순). <-10/null → 0. 매출·순이익 공용. */
    private static final List<Band> GROWTH_BANDS = List.of(
            new Band("20", 15), new Band("10", 12), new Band("5", 9), new Band("0", 6), new Band("-10", 3));

    // ─── 수익성 (35) ──────────────────────────────────────────────────────────────

    private InvestmentScore.Profitability profitability(AnnualStatement s) {
        int score = bandScore(s.operatingMargin(), Match.AT_LEAST, OPERATING_MARGIN_BANDS)
                + bandScore(s.roa(), Match.AT_LEAST, ROA_BANDS);
        return new InvestmentScore.Profitability(score, s.operatingMargin(), s.roa());
    }

    // ─── 안정성 (35) ──────────────────────────────────────────────────────────────

    private InvestmentScore.Stability stability(AnnualStatement s) {
        int score = bandScore(s.debtRatio(), Match.AT_MOST, DEBT_RATIO_BANDS)
                + bandScore(s.equityRatio(), Match.AT_LEAST, EQUITY_RATIO_BANDS);
        return new InvestmentScore.Stability(score, s.debtRatio(), s.equityRatio());
    }

    // ─── 성장성 (30) ──────────────────────────────────────────────────────────────

    private InvestmentScore.Growth growth(AnnualStatement latest, AnnualStatement previous) {
        if (previous == null) {
            // 직전 연도 부재 → 성장성을 판단할 수 없으므로 중립 50%(=15) 부여, 근거 지표는 null.
            return new InvestmentScore.Growth(InvestmentScore.Growth.MAX_SCORE / 2, null, null);
        }
        BigDecimal revenueGrowth = yoyGrowth(latest.revenue(), previous.revenue());
        BigDecimal netIncomeGrowth = yoyGrowth(latest.netIncome(), previous.netIncome());
        int score = bandScore(revenueGrowth, Match.AT_LEAST, GROWTH_BANDS)
                + bandScore(netIncomeGrowth, Match.AT_LEAST, GROWTH_BANDS);
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

    // ─── 밴드 조회 ────────────────────────────────────────────────────────────────

    /** 구간 매칭 방향 — 밴드 경계값을 넘어서는 쪽이 어디인지 정한다. */
    private enum Match {
        /** 값이 경계값 이상이면 매칭(높을수록 고득점). 테이블은 경계값 내림차순. */
        AT_LEAST,
        /** 값이 경계값 이하이면 매칭(낮을수록 고득점). 테이블은 경계값 오름차순. */
        AT_MOST
    }

    /** 구간 테이블 한 칸: 경계값과 매칭 시 부여 점수. */
    private record Band(BigDecimal threshold, int score) {
        Band(String threshold, int score) {
            this(new BigDecimal(threshold), score);
        }
    }

    /**
     * 밴드 테이블에서 값에 해당하는 점수를 찾는 유일한 조회 지점. 테이블 순서대로 첫 매칭 밴드의 점수를
     * 돌려주고, null 이거나 어떤 밴드에도 걸리지 않으면 0점(보수적)이다.
     */
    private static int bandScore(BigDecimal value, Match match, List<Band> bands) {
        if (value == null) {
            return 0;
        }
        for (Band band : bands) {
            boolean matched = match == Match.AT_LEAST
                    ? value.compareTo(band.threshold()) >= 0
                    : value.compareTo(band.threshold()) <= 0;
            if (matched) {
                return band.score();
            }
        }
        return 0;
    }
}
