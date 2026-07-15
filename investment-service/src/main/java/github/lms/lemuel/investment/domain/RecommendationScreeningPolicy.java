package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 규칙 스크리닝 통과 판정 + 이유문 생성 — 순수 도메인.
 *
 * <p>통과(규칙 5종) = 재무 투자적격(R1·R2, 투자점수 &ge; 60) + 악재뉴스 CLEAR(R3)
 * + 시세위치 OK(R4·R5, 추격·고점근접 아님) + 매매계획 성립.
 *
 * <p>뉴스·시세가 {@code NO_DATA}/{@code UNAVAILABLE} 이면 "악재 없음 / 적정 위치"를 보증할 근거가
 * 없으므로 <b>보수적으로 탈락</b>시킨다 — 예측이 아니라 규칙이므로 근거가 없는 종목은 추천하지 않는다.
 */
public class RecommendationScreeningPolicy {

    /** 통과 시 {@link ScreenedPick} 을, 한 규칙이라도 못 넘으면 빈 값을 돌려준다. */
    public Optional<ScreenedPick> evaluate(BeginnerInvestmentCheck check, String sector) {
        InvestmentScore score = check.score();
        if (score == null || !score.investable()) {
            return Optional.empty();
        }
        if (check.newsRisk().status() != NewsRiskCheck.Status.CLEAR) {
            return Optional.empty();
        }
        if (check.pricePosition().status() != PricePositionCheck.Status.OK) {
            return Optional.empty();
        }
        TradePlan plan = check.tradePlan();
        if (plan == null || !plan.feasible() || plan.entries().isEmpty()) {
            return Optional.empty();
        }

        BigDecimal entryPrice = plan.entries().get(0).targetPrice();
        return Optional.of(new ScreenedPick(
                score.stockCode(), score.companyName(), sector,
                buildReason(score, check.newsRisk()),
                entryPrice, plan.stopLossPrice(), plan.takeProfitPrice(),
                score.totalScore()));
    }

    private String buildReason(InvestmentScore score, NewsRiskCheck news) {
        return String.format(
                "투자점수 %d/100(%s) · 영업이익률 %s·ROA %s · 매출성장 %s. "
                        + "규칙 5종 통과(재무·악재뉴스·시세위치), 최근 뉴스 %d건 중 악재 없음 (FY%d · DART 연결)",
                score.totalScore(), score.grade(),
                pct(score.profitability().operatingMargin()), pct(score.profitability().roa()),
                signedPct(score.growth().revenueGrowth()),
                news.scannedCount(), score.fiscalYear());
    }

    private static String pct(BigDecimal value) {
        return value == null ? "—" : value.setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String signedPct(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal rounded = value.setScale(1, RoundingMode.HALF_UP);
        return (rounded.signum() >= 0 ? "+" : "") + rounded.toPlainString() + "%";
    }
}
