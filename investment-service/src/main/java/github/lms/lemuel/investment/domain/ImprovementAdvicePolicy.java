package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static github.lms.lemuel.investment.domain.ImprovementAdvice.Axis.GROWTH;
import static github.lms.lemuel.investment.domain.ImprovementAdvice.Axis.PROFITABILITY;
import static github.lms.lemuel.investment.domain.ImprovementAdvice.Axis.STABILITY;

/**
 * 투자받기 개선 포인트 산출 정책 — 순수 도메인.
 *
 * <p>{@link InvestmentScore} 의 근거 지표를 {@link InvestmentScorePolicy} 와 <b>동일한 구간표</b>에
 * 대입해, 지표별로 "다음 구간까지 얼마나 남았고 도달 시 몇 점이 오르는지"를 결정적으로 유도한다.
 * 최상 구간인 지표는 조언을 내지 않는다. 지표가 null(계정 미공시·산정 불가)이면 공시 보완이
 * 곧 개선 포인트다.
 *
 * <p>★ 이 클래스의 구간표는 {@link InvestmentScorePolicy} 의 구간과 1:1 이어야 한다 —
 * 드리프트는 {@code ImprovementAdvicePolicyTest} 의 교차검증 테스트가 빌드 시점에 잡는다.
 */
public class ImprovementAdvicePolicy {

    private record Step(BigDecimal threshold, int score) {
        static Step of(String threshold, int score) {
            return new Step(new BigDecimal(threshold), score);
        }
    }

    // 오름차순 구간표 — InvestmentScorePolicy 의 band 메서드들과 동치.
    private static final List<Step> OPERATING_MARGIN =
            List.of(Step.of("0", 4), Step.of("5", 8), Step.of("10", 12), Step.of("15", 16), Step.of("20", 20));
    private static final List<Step> ROA =
            List.of(Step.of("0", 3), Step.of("4", 6), Step.of("7", 9), Step.of("10", 12), Step.of("15", 15));
    private static final List<Step> EQUITY_RATIO =
            List.of(Step.of("20", 3), Step.of("30", 6), Step.of("40", 9), Step.of("50", 12), Step.of("60", 15));
    /** 부채비율은 낮을수록 고득점 — 임계값 이하(≤) 충족 방식. */
    private static final List<Step> DEBT_RATIO =
            List.of(Step.of("50", 20), Step.of("100", 16), Step.of("150", 12), Step.of("200", 8), Step.of("300", 4));
    private static final List<Step> GROWTH_RATE =
            List.of(Step.of("-10", 3), Step.of("0", 6), Step.of("5", 9), Step.of("10", 12), Step.of("20", 15));

    /** 축별 개선 포인트를 유도한다 — 수익성 → 안정성 → 성장성 순. 전 지표 최상 구간이면 빈 리스트. */
    public List<ImprovementAdvice> advise(InvestmentScore score) {
        List<ImprovementAdvice> advices = new ArrayList<>();

        InvestmentScore.Profitability p = score.profitability();
        gteAdvice(PROFITABILITY, "operatingMargin", "영업이익률", p.operatingMargin(), OPERATING_MARGIN,
                "이상 달성 시").ifPresent(advices::add);
        gteAdvice(PROFITABILITY, "roa", "ROA", p.roa(), ROA, "이상 달성 시").ifPresent(advices::add);

        InvestmentScore.Stability s = score.stability();
        debtRatioAdvice(s.debtRatio()).ifPresent(advices::add);
        gteAdvice(STABILITY, "equityRatio", "자기자본비율", s.equityRatio(), EQUITY_RATIO,
                "이상 확충 시").ifPresent(advices::add);

        advices.addAll(growthAdvices(score.growth()));
        return advices;
    }

    // ─── 성장성 ──────────────────────────────────────────────────────────────────

    private List<ImprovementAdvice> growthAdvices(InvestmentScore.Growth growth) {
        // 직전 연도 부재 → 중립 15점: 지표 개선이 아니라 "연속 공시 확보"가 개선 포인트다.
        boolean neutral = growth.score() == InvestmentScore.Growth.MAX_SCORE / 2
                && growth.revenueGrowth() == null && growth.netIncomeGrowth() == null;
        if (neutral) {
            return List.of(new ImprovementAdvice(GROWTH, "growthHistory",
                    "직전 연도 재무제표가 없어 성장성이 중립(15/30)으로 처리됨 — 2개 연도 연속 공시 확보 시 실적 기반 평가로 최대 +15점",
                    InvestmentScore.Growth.MAX_SCORE / 2));
        }
        List<ImprovementAdvice> advices = new ArrayList<>();
        gteAdvice(GROWTH, "revenueGrowth", "매출 성장률", growth.revenueGrowth(), GROWTH_RATE,
                "이상 성장 시").ifPresent(advices::add);
        gteAdvice(GROWTH, "netIncomeGrowth", "순이익 성장률", growth.netIncomeGrowth(), GROWTH_RATE,
                "이상 성장 시").ifPresent(advices::add);
        return advices;
    }

    // ─── 공통 유도 로직 ───────────────────────────────────────────────────────────

    /** 높을수록 좋은(≥) 지표 — 다음 구간 임계값과 점수 상승폭을 유도. 최상 구간이면 empty. */
    private Optional<ImprovementAdvice> gteAdvice(ImprovementAdvice.Axis axis, String metric, String label,
                                                  BigDecimal value, List<Step> steps, String verb) {
        int maxScore = steps.getLast().score();
        if (value == null) {
            return Optional.of(new ImprovementAdvice(axis, metric,
                    label + " 산정 불가(관련 계정 미공시 또는 산정 조건 미충족) — 공시·실적 확보 시 최대 +" + maxScore + "점",
                    maxScore));
        }
        int current = 0;
        for (Step step : steps) {
            if (value.compareTo(step.threshold()) >= 0) {
                current = step.score();
            }
        }
        for (Step step : steps) {
            if (value.compareTo(step.threshold()) < 0) {
                return Optional.of(new ImprovementAdvice(axis, metric,
                        label + " " + fmt(value) + "% → " + fmt(step.threshold()) + "% " + verb
                                + " +" + (step.score() - current) + "점",
                        step.score() - current));
            }
        }
        return Optional.empty(); // 최상 구간
    }

    /** 부채비율(≤, 낮을수록 좋음) — 한 단계 아래 임계값까지 낮출 때의 상승폭을 유도. */
    private Optional<ImprovementAdvice> debtRatioAdvice(BigDecimal value) {
        if (value == null) {
            return Optional.of(new ImprovementAdvice(STABILITY, "debtRatio",
                    "부채비율 산정 불가(관련 계정 미공시) — 공시 확보 시 최대 +" + DEBT_RATIO.getFirst().score() + "점",
                    DEBT_RATIO.getFirst().score()));
        }
        for (int i = 0; i < DEBT_RATIO.size(); i++) {
            if (value.compareTo(DEBT_RATIO.get(i).threshold()) <= 0) {
                if (i == 0) {
                    return Optional.empty(); // 최상 구간(≤50%)
                }
                Step target = DEBT_RATIO.get(i - 1);
                int gain = target.score() - DEBT_RATIO.get(i).score();
                return Optional.of(new ImprovementAdvice(STABILITY, "debtRatio",
                        "부채비율 " + fmt(value) + "% → " + fmt(target.threshold()) + "% 이하로 낮추면 +" + gain + "점",
                        gain));
            }
        }
        Step worst = DEBT_RATIO.getLast(); // 300% 초과 — 0점 구간
        return Optional.of(new ImprovementAdvice(STABILITY, "debtRatio",
                "부채비율 " + fmt(value) + "% → " + fmt(worst.threshold()) + "% 이하로 낮추면 +" + worst.score() + "점",
                worst.score()));
    }

    /** 소수 2자리 반올림 + 불필요한 0 제거 (60.00 → 60). */
    private static String fmt(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
