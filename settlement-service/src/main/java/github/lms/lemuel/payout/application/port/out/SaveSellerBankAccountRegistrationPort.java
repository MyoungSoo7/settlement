package github.lms.lemuel.payout.application.port.out;

import github.lms.lemuel.payout.domain.SellerBankAccountRegistration;

public interface SaveSellerBankAccountRegistrationPort {

    SellerBankAccountRegistration save(SellerBankAccountRegistration registration);
}
