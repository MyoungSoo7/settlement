package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;

public interface SaveDepositOffsetShortfallPort {
    DepositOffsetShortfall save(DepositOffsetShortfall shortfall);
}
