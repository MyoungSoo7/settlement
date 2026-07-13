package github.lms.lemuel.account.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 전표 금액 불변식 위반 — GL 분개 금액은 양수여야 한다(0·음수·null 금지).
 *
 * <p>기존 {@code IllegalArgumentException}(→ 공통 핸들러 400) 을 대체하며 상태코드/응답 계약은 동일하다.
 * 위반 금액을 {@link #getAmount()} 로 보존해 진단에 활용한다.
 */
public class NonPositiveEntryAmountException extends AccountDomainException {

    private final transient BigDecimal amount;

    public NonPositiveEntryAmountException(BigDecimal amount) {
        super(ErrorCode.INVALID_ARGUMENT, "전표 금액은 양수여야 합니다: " + amount);
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
