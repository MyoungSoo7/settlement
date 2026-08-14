package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.math.BigDecimal;

/**
 * 운용이율 불변식 위반 — 이율은 {@code [0, 1)} 구간의 소수(연 3.5% = 0.035)여야 한다.
 *
 * <p>퍼센트 표기(3.5)를 소수로 오인해 넣으면 적립금이 350% 로 불어나므로 상한을 1 미만으로 닫는다.
 * {@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class InvalidPensionRateException extends AccountDomainException {

    private final transient BigDecimal rate;

    public InvalidPensionRateException(BigDecimal rate) {
        super(ErrorCode.INVALID_ARGUMENT, "운용이율은 0 이상 1 미만의 소수여야 합니다: " + rate);
        this.rate = rate;
    }

    public BigDecimal getRate() {
        return rate;
    }
}
