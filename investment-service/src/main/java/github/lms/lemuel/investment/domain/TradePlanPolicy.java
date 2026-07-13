package github.lms.lemuel.investment.domain;

import github.lms.lemuel.investment.domain.exception.InvestmentInvariantViolationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 매매 계획 산정 정책 — 순수 도메인. kakaopay-invest-companion 플러그인의
 * {@code trade-plan.mjs}(순수 함수)를 BigDecimal 로 이식했다.
 *
 * <p>산정은 전부 결정적 규칙이다: 3분할 밴드(30/30/40 · 100%/95%/90%), 손절 평균가 ×0.93,
 * 익절 평균가 ×1.20, 모든 가격은 KRX 호가단위(2023-01 개편, 코스피·코스닥 공통) 내림.
 * 수수료·세금은 미반영.
 */
public class TradePlanPolicy {

    private record Band(String label, BigDecimal budgetShare, BigDecimal priceRatio) {
    }

    private static final List<Band> ENTRY_BANDS = List.of(
            new Band("1차", new BigDecimal("0.30"), BigDecimal.ONE),
            new Band("2차", new BigDecimal("0.30"), new BigDecimal("0.95")),
            new Band("3차", new BigDecimal("0.40"), new BigDecimal("0.90")));

    private static final BigDecimal STOP_LOSS_RATIO = new BigDecimal("0.93");
    private static final BigDecimal TAKE_PROFIT_RATIO = new BigDecimal("1.20");

    /**
     * 매매 계획을 산정한다.
     *
     * @param currentPrice 현재가(최신 종가, 원) — 필수, 양수
     * @param budget       총 예산(원) — null 이면 수량 없이 가격 레벨만 산정
     */
    public TradePlan plan(BigDecimal currentPrice, BigDecimal budget) {
        if (currentPrice == null || currentPrice.signum() <= 0) {
            throw new InvestmentInvariantViolationException("현재가가 유효하지 않습니다: " + currentPrice);
        }
        if (budget != null && budget.signum() <= 0) {
            throw new InvestmentInvariantViolationException("예산은 0 보다 커야 합니다: " + budget);
        }
        if (budget == null) {
            return priceLevelsOnly(currentPrice);
        }

        List<TradePlan.EntryBand> entries = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Band band : ENTRY_BANDS) {
            BigDecimal targetPrice = roundDownToTick(currentPrice.multiply(band.priceRatio()));
            int quantity = targetPrice.signum() > 0
                    ? budget.multiply(band.budgetShare()).divide(targetPrice, 0, RoundingMode.FLOOR).intValue()
                    : 0;
            BigDecimal amount = targetPrice.multiply(BigDecimal.valueOf(quantity));
            entries.add(new TradePlan.EntryBand(band.label(), targetPrice, band.budgetShare(), quantity, amount));
            totalQuantity += quantity;
            totalAmount = totalAmount.add(amount);
        }
        if (totalQuantity == 0) {
            return TradePlan.infeasible("예산 " + budget.toPlainString() + "원으로는 1주(현재가 "
                    + currentPrice.toPlainString() + "원)도 매수할 수 없습니다");
        }
        BigDecimal avgEntry = totalAmount.divide(BigDecimal.valueOf(totalQuantity), MathContext.DECIMAL64);
        return new TradePlan(true, null, List.copyOf(entries), totalQuantity, totalAmount,
                avgEntry.setScale(0, RoundingMode.HALF_UP),
                roundDownToTick(avgEntry.multiply(STOP_LOSS_RATIO)),
                roundDownToTick(avgEntry.multiply(TAKE_PROFIT_RATIO)));
    }

    /** 예산 미지정 — 가격 레벨 전용 모드. 평균가는 밴드 전량 체결 가정(30/30/40 가중). */
    private TradePlan priceLevelsOnly(BigDecimal currentPrice) {
        List<TradePlan.EntryBand> entries = new ArrayList<>();
        BigDecimal avgEntry = BigDecimal.ZERO;
        for (Band band : ENTRY_BANDS) {
            BigDecimal targetPrice = roundDownToTick(currentPrice.multiply(band.priceRatio()));
            entries.add(new TradePlan.EntryBand(band.label(), targetPrice, band.budgetShare(), null, null));
            avgEntry = avgEntry.add(targetPrice.multiply(band.budgetShare()));
        }
        return new TradePlan(true, null, List.copyOf(entries), null, null,
                avgEntry.setScale(0, RoundingMode.HALF_UP),
                roundDownToTick(avgEntry.multiply(STOP_LOSS_RATIO)),
                roundDownToTick(avgEntry.multiply(TAKE_PROFIT_RATIO)));
    }

    /** KRX 호가단위 (2023-01 개편 기준, 코스피·코스닥 공통). */
    static BigDecimal tickSize(BigDecimal price) {
        if (lt(price, 2_000)) {
            return BigDecimal.ONE;
        }
        if (lt(price, 5_000)) {
            return BigDecimal.valueOf(5);
        }
        if (lt(price, 20_000)) {
            return BigDecimal.TEN;
        }
        if (lt(price, 50_000)) {
            return BigDecimal.valueOf(50);
        }
        if (lt(price, 200_000)) {
            return BigDecimal.valueOf(100);
        }
        if (lt(price, 500_000)) {
            return BigDecimal.valueOf(500);
        }
        return BigDecimal.valueOf(1_000);
    }

    /** 호가단위 내림 — 결과는 정수 스케일로 정규화한다. */
    static BigDecimal roundDownToTick(BigDecimal price) {
        BigDecimal tick = tickSize(price);
        return price.divideToIntegralValue(tick).multiply(tick).setScale(0, RoundingMode.FLOOR);
    }

    private static boolean lt(BigDecimal price, long threshold) {
        return price.compareTo(BigDecimal.valueOf(threshold)) < 0;
    }
}
