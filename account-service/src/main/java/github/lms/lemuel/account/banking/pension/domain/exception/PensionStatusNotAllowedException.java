package github.lms.lemuel.account.banking.pension.domain.exception;

import github.lms.lemuel.account.banking.pension.domain.PensionStatus;
import github.lms.lemuel.account.domain.exception.AccountDomainException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 현재 계약 상태에서 성립하지 않는 조작 — 상태 전이 규칙 위반.
 *
 * <p>예: 수급 중 계약에 부담금 납입, 종료된 계약에 운용지시 변경, 적립 중 계약에 급여 지급.
 * {@code ErrorCode.INVALID_STATE}(400) 로 매핑된다.
 */
public class PensionStatusNotAllowedException extends AccountDomainException {

    private final PensionStatus status;
    private final String operation;

    public PensionStatusNotAllowedException(PensionStatus status, String operation) {
        super(ErrorCode.INVALID_STATE, "현재 상태(" + status + ")에서는 " + operation + " 을(를) 수행할 수 없습니다.");
        this.status = status;
        this.operation = operation;
    }

    public PensionStatus getStatus() {
        return status;
    }

    public String getOperation() {
        return operation;
    }
}
