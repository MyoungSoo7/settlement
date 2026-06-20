package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.SellerBankAccount;

import java.math.BigDecimal;

public interface RequestPayoutUseCase {

    /**
     * 정산 → Payout 전환. settlement_id 멱등 — 같은 정산은 1번만 생성.
     *
     * @return 새로 생성된 Payout (REQUESTED 상태). 이미 존재하면 기존 Payout 반환.
     */
    Payout requestForSettlement(Long settlementId, Long sellerId,
                                BigDecimal amount, SellerBankAccount account);
}
