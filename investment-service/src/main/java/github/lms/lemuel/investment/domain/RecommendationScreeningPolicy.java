package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 규칙 스크리닝 통과 판정 + 이유문 생성 — 순수 도메인.
 *
 * <p>통과 = 재무 투자적격(R1·R2, 투자점수 &ge; 60) + 악재뉴스 CLEAR(R3)
 * + 시세위치 판정 가능(R4·R5, OK 또는 CAUTION) + 매매계획 성립.
 *
 * <p>시세위치 {@code CAUTION}(추격 구간·52주 고점 근접)은 도메인 정의상 <b>탈락이 아니라
 * "진입 시점 주의" 신호</b>이므로 추천에 포함하되, 이유문에 "⚠️ 시세 주의"로 근거를 명시해 리스크를
 * 숨기지 않는다. 반면 뉴스가 CLEAR 가 아니거나(악재·판단불가) 시세가 {@code NO_DATA}/{@code UNAVAILABLE}
 * (종가 없음 → 매매계획 불가)이면 근거가 없으므로 <b>탈락</b>시킨다 — 예측이 아니라 규칙이다.
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
        PricePositionCheck price = check.pricePosition();
        if (price.status() != PricePositionCheck.Status.OK
                && price.status() != PricePositionCheck.Status.CAUTION) {
            return Optional.empty();
        }
        TradePlan plan = check.tradePlan();
        if (plan == null || !plan.feasible() || plan.entries().isEmpty()) {
            return Optional.empty();
        }

        BigDecimal entryPrice = plan.entries().get(0).targetPrice();
        return Optional.of(new ScreenedPick(
                score.stockCode(), score.companyName(), sector,
                buildReason(score, check.newsRisk(), price),
                entryPrice, plan.stopLossPrice(), plan.takeProfitPrice(),
                score.totalScore()));
    }

    private String buildReason(InvestmentScore score, NewsRiskCheck news, PricePositionCheck price) {
        String base = String.format(
                "투자점수 %d/100(%s) · 영업이익률 %s·ROA %s · 매출성장 %s. "
                        + "규칙 통과(재무·악재뉴스·시세위치), 최근 뉴스 %d건 중 악재 없음 (FY%d · DART 연결)",
                score.totalScore(), score.grade(),
                pct(score.profitability().operatingMargin()), pct(score.profitability().roa()),
                signedPct(score.growth().revenueGrowth()),
                news.scannedCount(), score.fiscalYear());
        String caution = cautionNote(price);
        return caution.isEmpty() ? base : base + " " + caution;
    }

    /** 시세위치 CAUTION 시 "⚠️ 시세 주의(추격/고점근접)" 근거 문구, 아니면 빈 문자열. */
    private static String cautionNote(PricePositionCheck price) {
        if (price.status() != PricePositionCheck.Status.CAUTION) {
            return "";
        }
        List<String> signals = new ArrayList<>();
        if (price.chaseRisk()) {
            signals.add("추격 구간(" + price.riseStreakDays() + "일 연속 상승)");
        }
        if (price.nearHigh()) {
            signals.add("52주 고점 근접(" + pct(price.highGapPercent()) + ")");
        }
        return "⚠️ 시세 주의 — " + (signals.isEmpty() ? "진입 시점 주의" : String.join(", ", signals))
                + " → 분할 진입 권장";
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
