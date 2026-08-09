package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 지급·인출 요청액이 적립금 잔액을 초과했다 — 적립금은 음수가 될 수 없다.
 *
 * <p>이 방어가 없으면 GL 의 {@code RETIREMENT_PENSION_LIABILITY} 가 차변 초과로 음수 부채가 되어
 * 시산표가 깨진다(서브원장이 GL 의 상한을 지키는 쪽이다).
 * {@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class PensionAmountExceedsAccumulatedException extends AccountDomainException {

    private final transient BigDecimal requested;
    private final transient BigDecimal accumulated;

    public PensionAmountExceedsAccumulatedException(BigDecimal requested, BigDecimal accumulated) {
        super(ErrorCode.INVALID_ARGUMENT,
                "요청 금액(" + requested + ")이 적립금 잔액(" + accumulated + ")을 초과합니다.");
        this.requested = requested;
        this.accumulated = accumulated;
    }

    public BigDecimal getRequested() {
        return requested;
    }

    public BigDecimal getAccumulated() {
        return accumulated;
    }
}
