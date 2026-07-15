package github.lms.lemuel.settlement.domain.exception;

import java.math.BigDecimal;

/**
 * 정산 조정(역정산) 금액 규약 위반 — {@link github.lms.lemuel.settlement.domain.SettlementAdjustment}
 * 의 {@code amount} 는 감사 규약상 항상 음수여야 하는데 0 또는 양수(또는 null)가 전달됐다.
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 규약을 어긴 금액을 {@link #getAmount()} 로 보존한다.
 */
public class NegativeAdjustmentAmountException extends SettlementInvariantViolationException {

    private final transient BigDecimal amount;

    public NegativeAdjustmentAmountException(BigDecimal amount) {
        super("amount 는 항상 음수여야 합니다 (역정산 감사 규약): " + amount);
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
