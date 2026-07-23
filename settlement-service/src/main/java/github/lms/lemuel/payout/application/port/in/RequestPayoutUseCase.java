package github.lms.lemuel.payout.application.port.in;

import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.payout.domain.SellerBankAccount;

import java.math.BigDecimal;
import java.util.Optional;

public interface RequestPayoutUseCase {

    /**
     * 정산 → Payout 전환. settlement_id 멱등 — 같은 정산은 1번만 생성.
     *
     * @return 새로 생성된 Payout (REQUESTED 상태, IMMEDIATE 유형). 이미 존재하면 기존 Payout 반환.
     */
    Payout requestForSettlement(Long settlementId, Long sellerId,
                                BigDecimal amount, SellerBankAccount account);

    /**
     * 정산 확정·홀드백 해제 경로에서 지급유형별 Payout 을 자동 생성한다.
     *
     * <p>(settlement, type) 멱등 — 같은 정산·유형은 최대 1건. 계좌는 {@code LoadSellerBankAccountPort}
     * 로 해석한다(호출자는 계좌를 몰라도 된다). 금액이 0 이하이거나 계좌를 해석하지 못하면 생성하지
     * 않고 {@link Optional#empty()} 를 반환한다(반쪽 지급 방지).
     *
     * @param amount 지급액(양수만 생성). IMMEDIATE=즉시지급액, HOLDBACK_RELEASE=잔여 보류액.
     * @return 생성되었거나 이미 존재하던 Payout. 미생성이면 empty.
     */
    Optional<Payout> requestPayoutOfType(Long settlementId, Long sellerId,
                                         BigDecimal amount, PayoutType payoutType);
}
