package github.lms.lemuel.deposit.application.port.out;

import github.lms.lemuel.deposit.domain.SellerDepositAccount;

import java.util.Optional;

public interface LoadDepositAccountPort {
    Optional<SellerDepositAccount> findBySellerId(Long sellerId);
    /** 비관적 락으로 조회 — 동시 write 직렬화에 사용. */
    Optional<SellerDepositAccount> findBySellerIdForUpdate(Long sellerId);

    /**
     * 계좌 PK 로 비관적 락 조회.
     *
     * <p>hold 는 sellerId 가 아니라 accountId 를 들고 있다. 만료 회수처럼 hold 에서 출발하는
     * 경로는 sellerId 를 되짚을 방법이 없으므로 PK 진입점이 필요하다.
     */
    Optional<SellerDepositAccount> findByIdForUpdate(Long accountId);
}
