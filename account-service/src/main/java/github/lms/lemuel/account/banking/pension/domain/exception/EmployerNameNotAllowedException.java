package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.banking.pension.domain.PensionScheme;
import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 개인형(IRP)인데 사업장명이 들어왔다 — IRP 는 가입자 본인 계정이라 사업장이 존재하지 않는다.
 *
 * <p>조용히 무시하면 "사업장이 있는 IRP" 라는 잘못된 데이터가 남으므로 명시적으로 거절한다.
 * {@code ErrorCode.INVALID_ARGUMENT}(400) 로 매핑된다.
 */
public class EmployerNameNotAllowedException extends AccountDomainException {

    private final PensionScheme scheme;

    public EmployerNameNotAllowedException(PensionScheme scheme) {
        super(ErrorCode.INVALID_ARGUMENT, scheme + "형 퇴직연금에는 사업장명을 지정할 수 없습니다.");
        this.scheme = scheme;
    }

    public PensionScheme getScheme() {
        return scheme;
    }
}
