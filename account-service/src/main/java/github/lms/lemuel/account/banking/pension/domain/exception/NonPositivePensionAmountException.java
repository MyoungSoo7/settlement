package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 퇴직연금 거래 금액 불변식 위반 — 납입·이자·지급·인출 금액은 원 단위로 반올림한 뒤에도 양수여야 한다.
 *
 * <p>0.4원처럼 원 단위 반올림 시 0 이 되는 금액도 거절한다 — 통과시키면 금액 0 인 서브원장 거래가
 * 남는데 GL 팩토리는 양수만 받으므로 서브원장과 GL 이 곧바로 어긋난다.
 * {@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class NonPositivePensionAmountException extends AccountDomainException {

    private final transient BigDecimal amount;

    public NonPositivePensionAmountException(BigDecimal amount) {
        super(ErrorCode.INVALID_ARGUMENT, "퇴직연금 거래 금액은 원 단위 양수여야 합니다: " + amount);
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
