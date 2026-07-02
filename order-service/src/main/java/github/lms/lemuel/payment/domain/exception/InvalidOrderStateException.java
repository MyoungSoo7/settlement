package github.lms.lemuel.payment.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class InvalidOrderStateException extends BusinessException {
    public InvalidOrderStateException(String message) {
        super(ErrorCode.INVALID_ORDER_STATE, message);
    }
}
