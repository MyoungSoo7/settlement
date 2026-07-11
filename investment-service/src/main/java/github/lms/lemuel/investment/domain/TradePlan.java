package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 매매 계획 — 가격 예측이 아니라 <b>규칙</b>이다.
 *
 * <ul>
 *   <li>진입: 3분할 매입 밴드 (1차 30% 현재가, 2차 30% −5%, 3차 40% −10%)</li>
 *   <li>손절: 평균 매수가 −7% 도달 시 전량 매도</li>
 *   <li>익절: 평균 매수가 +20% 도달 시 절반 매도, 잔여분 손절선은 본전으로 상향</li>
 * </ul>
 *
 * <p>모든 가격은 KRX 호가단위 내림. 예산 없이 산정하면 가격 레벨만 담기고
 * 수량 필드({@code quantity}/{@code totalQuantity}/{@code totalAmount})는 null 이다.
 */
public record TradePlan(boolean feasible, String infeasibleReason, List<EntryBand> entries,
                        Integer totalQuantity, BigDecimal totalAmount, BigDecimal avgEntryPrice,
                        BigDecimal stopLossPrice, BigDecimal takeProfitPrice) {

    /** 분할 매입 밴드 1단 — 예산 미지정 시 quantity/amount 는 null. */
    public record EntryBand(String label, BigDecimal targetPrice, BigDecimal budgetShare,
                            Integer quantity, BigDecimal amount) {
    }

    public static TradePlan infeasible(String reason) {
        return new TradePlan(false, reason, List.of(), null, null, null, null, null);
    }
}
