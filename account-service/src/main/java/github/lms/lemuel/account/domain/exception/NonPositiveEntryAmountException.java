package github.lms.lemuel.account.domain.exception;

import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 전표 금액 불변식 위반 — GL 분개 금액은 양수여야 한다(0·음수·null 금지).
 *
 * <p>{@code ErrorCode.NON_POSITIVE_ENTRY_AMOUNT}(400) 로 매핑된다 — 기존 {@code INVALID_ARGUMENT}
 * 와 동일한 400 상태를 유지하되 계정계 전용 코드를 카탈로그에서 부여한다(account 는 소비 전용이라 웹 노출 경로 없음).
 * 위반 금액을 {@link #getAmount()} 로 보존해 진단에 활용한다.
 */
public class NonPositiveEntryAmountException extends AccountDomainException {

    private final transient BigDecimal amount;

    public NonPositiveEntryAmountException(BigDecimal amount) {
        super(ErrorCode.NON_POSITIVE_ENTRY_AMOUNT, "전표 금액은 양수여야 합니다: " + amount);
        this.amount = amount;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
