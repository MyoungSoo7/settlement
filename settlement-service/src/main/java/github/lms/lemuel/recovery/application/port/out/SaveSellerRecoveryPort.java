package github.lms.lemuel.recovery.application.port.out;

import github.lms.lemuel.recovery.domain.SellerRecovery;

public interface SaveSellerRecoveryPort {

    SellerRecovery save(SellerRecovery recovery);
}
