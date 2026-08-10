package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.DepositShortfallStatus;

import java.util.List;

public interface LoadDepositOffsetShortfallPort {
    List<DepositOffsetShortfall> findByStatus(DepositShortfallStatus status);
}
