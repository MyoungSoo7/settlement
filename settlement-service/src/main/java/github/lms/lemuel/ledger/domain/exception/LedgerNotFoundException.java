package github.lms.lemuel.ledger.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class LedgerNotFoundException extends BusinessException {

    public LedgerNotFoundException(Long id) {
        super(ErrorCode.LEDGER_NOT_FOUND, "LedgerEntry not found: id=" + id);
    }

    public LedgerNotFoundException(String message) {
        super(ErrorCode.LEDGER_NOT_FOUND, message);
    }
}
