package github.lms.lemuel.deposit.application.port.in;

import github.lms.lemuel.deposit.domain.SellerDepositAccount;

import java.util.Optional;

/**
 * 예치 계좌 조회 유스케이스.
 */
public interface QueryDepositAccountUseCase {
    Optional<SellerDepositAccount> findBySellerId(Long sellerId);
}
