package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.MarginCall;
import github.lms.lemuel.loan.domain.MarginCallStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 마진콜 영속 엔티티. 대출당 활성(OPEN) 1건 유일성은 부분 유니크 인덱스
 * {@code uq_margin_call_open_per_loan} 가 DB 에서 보장한다.
 */
@Entity
@Table(name = "margin_calls")
public class MarginCallJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(name = "collateral_id", nullable = false)
    private Long collateralId;

    @Column(name = "required_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal requiredAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MarginCallStatus status;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected MarginCallJpaEntity() { }

    private MarginCallJpaEntity(Long id, Long loanId, Long collateralId, BigDecimal requiredAmount,
                                MarginCallStatus status, LocalDateTime openedAt,
                                LocalDateTime closedAt, LocalDateTime createdAt) {
        this.id = id;
        this.loanId = loanId;
        this.collateralId = collateralId;
        this.requiredAmount = requiredAmount;
        this.status = status;
        this.openedAt = openedAt;
        this.closedAt = closedAt;
        this.createdAt = createdAt;
    }

    public static MarginCallJpaEntity from(MarginCall marginCall) {
        return new MarginCallJpaEntity(marginCall.getId(), marginCall.getLoanId(),
                marginCall.getCollateralId(), marginCall.getRequiredAmount(), marginCall.getStatus(),
                marginCall.getOpenedAt(), marginCall.getClosedAt(), LocalDateTime.now());
    }

    public MarginCall toDomain() {
        return MarginCall.reconstitute(id, loanId, collateralId, requiredAmount, status,
                openedAt, closedAt);
    }
}
