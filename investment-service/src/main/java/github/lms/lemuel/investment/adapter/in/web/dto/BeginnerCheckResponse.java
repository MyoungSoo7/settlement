package github.lms.lemuel.investment.adapter.in.web.dto;

import github.lms.lemuel.investment.domain.BeginnerInvestmentCheck;
import github.lms.lemuel.investment.domain.MacroCheck;
import github.lms.lemuel.investment.domain.NewsRiskCheck;
import github.lms.lemuel.investment.domain.PricePositionCheck;
import github.lms.lemuel.investment.domain.TradePlan;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 초보 투자 체크 응답 — 투자점수 + 악재 뉴스(R3) + 시세 위치(R4·R5) + 거시 + 매매계획.
 * 고지문은 응답 자체에 필수 포함한다(소비 화면이 어디든 누락 불가).
 */
public record BeginnerCheckResponse(
        String stockCode,
        InvestmentScoreResponse score,
        NewsRisk newsRisk,
        PricePosition pricePosition,
        Macro macro,
        TradePlanBody tradePlan,
        String disclaimer) {

    public static final String DISCLAIMER =
            "본 정보는 교육 목적의 정보 제공이며 투자자문·투자권유가 아닙니다. "
                    + "투자 판단과 그 결과(손실 포함)에 대한 책임은 투자자 본인에게 있습니다.";

    private static final String STOP_LOSS_RULE =
            "평균 매수가 -7% 도달 시 전량 매도 — 예외를 만들지 않는다";
    private static final String TAKE_PROFIT_RULE =
            "평균 매수가 +20% 도달 시 절반 매도, 잔여분 손절선은 본전(평균 매수가)으로 상향";
    private static final List<String> TRADE_PLAN_NOTES = List.of(
            "이 가격들은 예측이 아니라 규칙이다 — 2·3차 밴드에 안 오면 그 비중은 집행하지 않는 것이 원칙",
            "모든 가격은 KRX 호가단위 내림 적용, 수수료·세금 미반영",
            "시세는 market-service 일별 종가 기준(실시간 아님)");

    public record NewsRisk(String status, int scannedCount, List<Flag> flags) {
        public record Flag(String keyword, String title, String url, Instant publishedAt) {
        }

        static NewsRisk from(NewsRiskCheck check) {
            return new NewsRisk(check.status().name(), check.scannedCount(),
                    check.flags().stream()
                            .map(f -> new Flag(f.keyword(), f.title(), f.url(), f.publishedAt()))
                            .toList());
        }
    }

    public record PricePosition(String status, LocalDate baseDate, BigDecimal latestClose,
                                int riseStreakDays, boolean chaseRisk,
                                BigDecimal fiftyTwoWeekHigh, BigDecimal fiftyTwoWeekLow,
                                BigDecimal highGapPercent, boolean nearHigh) {
        static PricePosition from(PricePositionCheck check) {
            return new PricePosition(check.status().name(), check.baseDate(), check.latestClose(),
                    check.riseStreakDays(), check.chaseRisk(), check.fiftyTwoWeekHigh(),
                    check.fiftyTwoWeekLow(), check.highGapPercent(), check.nearHigh());
        }
    }

    public record Macro(String status, List<Indicator> indicators) {
        public record Indicator(String code, String name, String unit, BigDecimal value,
                                LocalDate observedDate, BigDecimal changeAmount) {
        }

        static Macro from(MacroCheck check) {
            return new Macro(check.status().name(),
                    check.indicators().stream()
                            .map(i -> new Indicator(i.code(), i.name(), i.unit(), i.value(),
                                    i.observedDate(), i.changeAmount()))
                            .toList());
        }
    }

    public record TradePlanBody(boolean feasible, String infeasibleReason, List<Entry> entries,
                                Integer totalQuantity, BigDecimal totalAmount, BigDecimal avgEntryPrice,
                                BigDecimal stopLossPrice, String stopLossRule,
                                BigDecimal takeProfitPrice, String takeProfitRule,
                                List<String> notes) {
        public record Entry(String label, BigDecimal targetPrice, BigDecimal budgetShare,
                            Integer quantity, BigDecimal amount) {
        }

        static TradePlanBody from(TradePlan plan) {
            return new TradePlanBody(plan.feasible(), plan.infeasibleReason(),
                    plan.entries().stream()
                            .map(e -> new Entry(e.label(), e.targetPrice(), e.budgetShare(),
                                    e.quantity(), e.amount()))
                            .toList(),
                    plan.totalQuantity(), plan.totalAmount(), plan.avgEntryPrice(),
                    plan.stopLossPrice(), plan.feasible() ? STOP_LOSS_RULE : null,
                    plan.takeProfitPrice(), plan.feasible() ? TAKE_PROFIT_RULE : null,
                    plan.feasible() ? TRADE_PLAN_NOTES : List.of());
        }
    }

    public static BeginnerCheckResponse from(BeginnerInvestmentCheck check) {
        return new BeginnerCheckResponse(
                check.stockCode(),
                InvestmentScoreResponse.from(check.score()),
                NewsRisk.from(check.newsRisk()),
                PricePosition.from(check.pricePosition()),
                Macro.from(check.macro()),
                check.tradePlan() == null ? null : TradePlanBody.from(check.tradePlan()),
                DISCLAIMER);
    }
}
