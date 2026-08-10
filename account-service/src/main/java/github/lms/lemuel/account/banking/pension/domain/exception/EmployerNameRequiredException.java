package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 사업장 퇴직연금(DB·DC)인데 사업장명이 없다 — 제도 자체가 사용자(사업장)를 전제하므로 성립할 수 없다.
 *
 * <p>{@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class EmployerNameRequiredException extends AccountDomainException {

    private final PensionScheme scheme;

    public EmployerNameRequiredException(PensionScheme scheme) {
        super(ErrorCode.INVALID_ARGUMENT, scheme + "형 퇴직연금은 사업장명이 필수입니다.");
        this.scheme = scheme;
    }

    public PensionScheme getScheme() {
        return scheme;
    }
}
