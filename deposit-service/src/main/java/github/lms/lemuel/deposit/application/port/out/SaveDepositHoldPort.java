package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositHold;

public interface SaveDepositHoldPort {
    DepositHold save(DepositHold hold);
}
