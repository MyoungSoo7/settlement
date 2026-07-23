package github.lms.lemuel.payout.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 송금 반송(bounce) 영속 엔티티. {@code payout_id} UNIQUE 가 "반송당 정확히 한 번" 멱등을 DB 로 강제한다.
 * {@code resolved_payout_id} 는 재발행 payout 링크(set-once) — 재지급 후 채워진다.
 */
@Entity
@Table(name = "payout_bounces")
public class PayoutBounceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_id", nullable = false, unique = true)
    private Long payoutId;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "resolved_payout_id")
    private Long resolvedPayoutId;

    @Column(name = "operator_id", length = 100)
    private String operatorId;

    @Column(name = "bounced_at", nullable = false)
    private LocalDateTime bouncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected PayoutBounceJpaEntity() {
    }

    public PayoutBounceJpaEntity(Long id, Long payoutId, String reason, Long resolvedPayoutId,
                                 String operatorId, LocalDateTime bouncedAt, LocalDateTime createdAt) {
        this.id = id;
        this.payoutId = payoutId;
        this.reason = reason;
        this.resolvedPayoutId = resolvedPayoutId;
        this.operatorId = operatorId;
        this.bouncedAt = bouncedAt;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getPayoutId() { return payoutId; }
    public String getReason() { return reason; }
    public Long getResolvedPayoutId() { return resolvedPayoutId; }
    public String getOperatorId() { return operatorId; }
    public LocalDateTime getBouncedAt() { return bouncedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    /** 재발행 payout 링크 반영 — 반송 기록 후 재지급 payout 생성 시 채운다(set-once). */
    public void applyResolved(Long resolvedPayoutId) {
        this.resolvedPayoutId = resolvedPayoutId;
    }
}
