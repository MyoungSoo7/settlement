package github.lms.lemuel.settlement.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class SettlementNotFoundException extends BusinessException {
    public SettlementNotFoundException(String message) {
        super(ErrorCode.SETTLEMENT_NOT_FOUND, message);
    }

    public SettlementNotFoundException(Long settlementId) {
        super(ErrorCode.SETTLEMENT_NOT_FOUND, "Settlement not found with id: " + settlementId);
    }
}
