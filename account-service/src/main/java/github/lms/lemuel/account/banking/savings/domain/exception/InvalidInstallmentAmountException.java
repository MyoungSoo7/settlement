package github.lms.lemuel.account.banking.savings.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 회차 납입 금액이 적립 방식의 규칙을 위반 — FIXED 인데 약정액과 다르거나, FLEXIBLE 인데 0 이하·한도 초과.
 *
 * <p>{@code ErrorCode.INVALID_ARGUMENT}(400). 위반 금액을 보존해 진단에 쓴다.
 */
public class InvalidInstallmentAmountException extends AccountSavingsDomainException {

    private final transient BigDecimal amount;

    public InvalidInstallmentAmountException(BigDecimal amount, String reason) {
        super(ErrorCode.INVALID_ARGUMENT, "회차 납입 금액이 올바르지 않습니다(" + reason + "): " + amount);
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
