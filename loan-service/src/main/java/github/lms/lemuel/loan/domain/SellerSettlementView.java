package github.lms.lemuel.loan.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import github.lms.lemuel.loan.domain.exception.LoanInvariantViolationException;

/**
 * 셀러별 정산건의 로컬 투영(read model). settlement 의 SettlementCreated/Confirmed 이벤트로
 * loan 자체 DB 에 materialize 되며, 한도 산정(미지급 합계)과 상환 트리거의 근거가 된다.
 *
 * <p>순수 POJO. {@code settlementId} 가 식별자이며 이벤트 재수신 시 멱등 UPSERT 된다.
 */
public class SellerSettlementView {

    private final Long settlementId;
    private final Long sellerId;
    private final BigDecimal amount;
    private final LocalDate dueDate;
    private SettlementViewStatus status;

    private SellerSettlementView(Long settlementId, Long sellerId, BigDecimal amount,
                                 LocalDate dueDate, SettlementViewStatus status) {
        this.settlementId = settlementId;
        this.sellerId = sellerId;
        this.amount = amount;
        this.dueDate = dueDate;
        this.status = status;
    }

    /** SettlementCreated 수신 — 미지급(PENDING) 투영 생성. */
    public static SellerSettlementView pending(Long settlementId, Long sellerId,
                                               BigDecimal amount, LocalDate dueDate) {
        if (settlementId == null || sellerId == null) {
            throw new LoanInvariantViolationException("settlementId/sellerId 는 필수입니다");
        }
        if (amount == null || amount.signum() < 0) {
            throw new LoanInvariantViolationException("정산금은 음수일 수 없습니다: " + amount);
        }
        return new SellerSettlementView(settlementId, sellerId, amount, dueDate, SettlementViewStatus.PENDING);
    }

    /** 영속 상태 재구성(리포지토리 전용). */
    public static SellerSettlementView reconstitute(Long settlementId, Long sellerId, BigDecimal amount,
                                                    LocalDate dueDate, SettlementViewStatus status) {
        return new SellerSettlementView(settlementId, sellerId, amount, dueDate, status);
    }

    public void confirm() {
        this.status = SettlementViewStatus.CONFIRMED;
    }

    public Long getSettlementId() { return settlementId; }
    public Long getSellerId() { return sellerId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getDueDate() { return dueDate; }
    public SettlementViewStatus getStatus() { return status; }
}
