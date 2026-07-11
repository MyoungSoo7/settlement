package github.lms.lemuel.investment.domain;

import java.math.BigDecimal;

/**
 * settlement 확정 정산금의 로컬 투영(read model) — 투자 재원의 원천.
 *
 * <p>settlement 의 {@code lemuel.settlement.confirmed} 이벤트로 investment 자체 DB 에 materialize 되며,
 * {@code settlementId} 가 식별자라 이벤트 재수신 시 멱등 UPSERT 된다. 순수 POJO.
 */
public class SellerFundingView {

    private final Long settlementId;
    private final Long sellerId;
    private final BigDecimal amount;
    private final FundingViewStatus status;

    private SellerFundingView(Long settlementId, Long sellerId, BigDecimal amount, FundingViewStatus status) {
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.status = status;
    }

    /** settlement.confirmed 수신 — 확정 재원 투영 생성. */
    public static SellerFundingView confirmed(Long settlementId, Long sellerId, BigDecimal amount) {
        if (settlementId == null || sellerId == null) {
            throw new IllegalArgumentException("settlementId/sellerId 는 필수입니다");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("정산금은 음수일 수 없습니다: " + amount);
        }
        return new SellerFundingView(settlementId, sellerId, amount, FundingViewStatus.CONFIRMED);
    }

    /** 영속 상태 재구성(리포지토리 전용). */
    public static SellerFundingView reconstitute(Long settlementId, Long sellerId, BigDecimal amount,
                                                 FundingViewStatus status) {
        return new SellerFundingView(settlementId, sellerId, amount, status);
    }

    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public FundingViewStatus getStatus() { return status; }
}
