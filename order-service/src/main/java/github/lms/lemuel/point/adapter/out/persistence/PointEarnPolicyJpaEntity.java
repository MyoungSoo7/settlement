package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** {@code point_earn_policy} 매핑 — 행 UPDATE 금지, 변경은 close + 신규 행(ADR 0032 규약). */
@Entity
@Table(name = "point_earn_policy")
public class PointEarnPolicyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private PointEarnScope scope;

    @Column(name = "scope_key", nullable = false, length = 64)
    private String scopeKey;

    @Column(name = "earn_rate", nullable = false, precision = 6, scale = 5)
    private BigDecimal earnRate;

    @Column(name = "validity_days", nullable = false)
    private int validityDays;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    protected PointEarnPolicyJpaEntity() {
    }

    PointEarnPolicy toDomain() {
        return PointEarnPolicy.rehydrate(id, scope, scopeKey, earnRate, validityDays,
                effectiveFrom, effectiveTo, reason, createdBy);
    }
}
