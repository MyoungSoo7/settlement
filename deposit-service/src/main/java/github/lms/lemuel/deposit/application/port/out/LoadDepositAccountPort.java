package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.SellerDepositAccount;

import java.util.Optional;

public interface LoadDepositAccountPort {
    Optional<SellerDepositAccount> findBySellerId(Long sellerId);
    /** 비관적 락으로 조회 — 동시 write 직렬화에 사용. */
    Optional<SellerDepositAccount> findBySellerIdForUpdate(Long sellerId);
}
