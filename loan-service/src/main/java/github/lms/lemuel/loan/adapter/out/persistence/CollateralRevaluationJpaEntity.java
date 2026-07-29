package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.CollateralRevaluation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 담보 재평가 이력 영속 엔티티. <b>append-only</b> — 갱신·삭제 경로가 없다.
 */
@Entity
@Table(name = "collateral_revaluations")
public class CollateralRevaluationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "collateral_id", nullable = false)
    private Long collateralId;

    @Column(name = "revalued_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal revaluedValue;

    @Column(name = "source", nullable = false, length = 40)
    private String source;

    @Column(name = "revalued_at", nullable = false)
    private LocalDateTime revaluedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected CollateralRevaluationJpaEntity() { }

    private CollateralRevaluationJpaEntity(Long collateralId, BigDecimal revaluedValue, String source,
                                           LocalDateTime revaluedAt, LocalDateTime createdAt) {
        this.collateralId = collateralId;
        this.revaluedValue = revaluedValue;
        this.source = source;
        this.revaluedAt = revaluedAt;
        this.createdAt = createdAt;
    }

    public static CollateralRevaluationJpaEntity from(CollateralRevaluation revaluation) {
        return new CollateralRevaluationJpaEntity(revaluation.collateralId(), revaluation.revaluedValue(),
                revaluation.source(), revaluation.revaluedAt(), LocalDateTime.now());
    }

    public CollateralRevaluation toDomain() {
        return CollateralRevaluation.of(collateralId, revaluedValue, source, revaluedAt);
    }

    public BigDecimal getRevaluedValue() {
        return revaluedValue;
    }
}
