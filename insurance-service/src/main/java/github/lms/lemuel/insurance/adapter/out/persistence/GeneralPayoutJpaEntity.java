package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import github.lms.lemuel.insurance.domain.GeneralPayoutType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * general_payouts 테이블 매핑 (V10).
 *
 * <p>산출근거 스냅샷(D-G5)은 생성 후 불변 — 도메인 전이 결과 반영은
 * {@link #applyTransitionResult} 로 status·paid_on 만 쓴다.
 *
 * <p>멱등 최후 방어: {@code uq_general_payout_policy_type UNIQUE(policy_id, payout_type)} —
 * 같은 계약·유형의 이중 INSERT 는 DB 가 거부한다.
 */
@Entity
@Table(name = "general_payouts", schema = "opslab")
public class GeneralPayoutJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_id", nullable = false)
    private UUID payoutId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "policy_number", nullable = false, length = 64)
    private String policyNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_type", nullable = false, length = 32)
    private GeneralPayoutType payoutType;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_premium_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal paidPremiumTotal;

    @Column(name = "applied_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal appliedRate;

    @Column(name = "elapsed_months", nullable = false)
    private long elapsedMonths;

    @Column(name = "installment_count", nullable = false)
    private int installmentCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GeneralPayoutStatus status;

    @Column(name = "requested_on", nullable = false)
    private LocalDate requestedOn;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected GeneralPayoutJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** 신규 지급 요청 INSERT 용 — Policy terminal 전이 경로 전용. */
    public static GeneralPayoutJpaEntity fromRequested(GeneralPayout payout) {
        GeneralPayoutJpaEntity e = new GeneralPayoutJpaEntity();
        e.payoutId = UUID.fromString(payout.getPayoutId());
        e.policyId = UUID.fromString(payout.getPolicyId());
        e.policyNumber = payout.getPolicyNumber();
        e.payoutType = payout.getPayoutType();
        e.amount = payout.getAmount();
        e.paidPremiumTotal = payout.getPaidPremiumTotal();
        e.appliedRate = payout.getAppliedRate();
        e.elapsedMonths = payout.getElapsedMonths();
        e.installmentCount = payout.getInstallmentCount();
        e.status = payout.getStatus();
        e.requestedOn = payout.getRequestedOn();
        e.paidOn = payout.getPaidOn();
        return e;
    }

    /** DB 행 → 도메인 재구성. 전이 로직은 도메인이 강제한다. */
    public GeneralPayout toDomain() {
        return GeneralPayout.builder()
                .id(id)
                .payoutId(payoutId.toString())
                .policyId(policyId.toString())
                .policyNumber(policyNumber)
                .payoutType(payoutType)
                .amount(amount)
                .paidPremiumTotal(paidPremiumTotal)
                .appliedRate(appliedRate)
                .elapsedMonths(elapsedMonths)
                .installmentCount(installmentCount)
                .status(status)
                .requestedOn(requestedOn)
                .paidOn(paidOn)
                .build();
    }

    /**
     * 도메인 전이 결과 반영 — 전이로 변할 수 있는 2개 필드만.
     * 산출근거 스냅샷(D-G5)은 그대로 보존된다.
     */
    public void applyTransitionResult(GeneralPayout payout) {
        this.status = payout.getStatus();
        this.paidOn = payout.getPaidOn();
    }

    public Long getId() {
        return id;
    }

    public UUID getPayoutId() {
        return payoutId;
    }

    public GeneralPayoutStatus getStatus() {
        return status;
    }
}
