package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositEntry;
import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.SellerDepositAccount;

public interface PublishDepositEventPort {
    void publishBalanceChanged(SellerDepositAccount account, String triggerEventType);
    void publishHoldPlaced(DepositHold hold, SellerDepositAccount account);
    void publishHoldReleased(DepositHold hold, SellerDepositAccount account);
    void publishOffsetApplied(DepositEntry entry, SellerDepositAccount account);
    void publishOffsetShortfall(DepositOffsetShortfall shortfall);
}
