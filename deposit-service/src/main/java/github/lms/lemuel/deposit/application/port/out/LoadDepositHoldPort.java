package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositHolderType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LoadDepositHoldPort {
    Optional<DepositHold> findByHolderTypeAndReference(DepositHolderType holderType, String holderReference);
    List<DepositHold> findActiveExpiredBefore(LocalDateTime cutoff);
}
