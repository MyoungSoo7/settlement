package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.SellerDepositAccount;

public interface SaveDepositAccountPort {
    SellerDepositAccount save(SellerDepositAccount account);
}
