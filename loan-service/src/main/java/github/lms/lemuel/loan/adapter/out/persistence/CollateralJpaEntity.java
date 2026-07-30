package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.Collateral;
import github.lms.lemuel.loan.domain.CollateralStatus;
import github.lms.lemuel.loan.domain.CollateralType;
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
 * 담보 영속 엔티티. 도메인({@link Collateral})과 1:1 평탄 매핑 — 도메인은 JPA 를 모른다.
 */
@Entity
@Table(name = "collaterals")
public class CollateralJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private CollateralType type;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "appraised_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal appraisedValue;

    @Column(name = "senior_claim_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal seniorClaimAmount;

    @Column(name = "appraised_at", nullable = false)
    private LocalDateTime appraisedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CollateralStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CollateralJpaEntity() { }

    private CollateralJpaEntity(Long id, CollateralType type, String description, BigDecimal appraisedValue,
                                BigDecimal seniorClaimAmount, LocalDateTime appraisedAt,
                                CollateralStatus status, LocalDateTime createdAt) {
        this.id = id;
        this.type = type;
        this.description = description;
        this.appraisedValue = appraisedValue;
        this.seniorClaimAmount = seniorClaimAmount;
        this.appraisedAt = appraisedAt;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static CollateralJpaEntity from(Collateral collateral) {
        return new CollateralJpaEntity(collateral.getId(), collateral.getType(), collateral.getDescription(),
                collateral.getAppraisedValue(), collateral.getSeniorClaimAmount(),
                collateral.getAppraisedAt(), collateral.getStatus(), LocalDateTime.now());
    }

    public Collateral toDomain() {
        return Collateral.reconstitute(id, type, description, appraisedValue, seniorClaimAmount,
                appraisedAt, status);
    }

    public Long getId() { return id; }
}
