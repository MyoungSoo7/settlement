package github.lms.lemuel.economics.adapter.out.persistence;

import github.lms.lemuel.economics.domain.IndicatorValue;
import github.lms.lemuel.economics.domain.ValueSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "indicator_values",
        uniqueConstraints = @UniqueConstraint(name = "uq_iv_indicator_date",
                columnNames = {"indicator_code", "observed_date"}))
public class IndicatorValueJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "indicator_code", nullable = false, length = 30)
    private String indicatorCode;

    @Column(name = "observed_date", nullable = false)
    private LocalDate observedDate;

    @Column(name = "value", nullable = false, precision = 18, scale = 4)
    private BigDecimal value;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private ValueSource source;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    protected IndicatorValueJpaEntity() {
    }

    static IndicatorValueJpaEntity fromDomain(IndicatorValue value) {
        IndicatorValueJpaEntity entity = new IndicatorValueJpaEntity();
        entity.indicatorCode = value.indicatorCode();
        entity.observedDate = value.observedDate();
        entity.applyDomain(value);
        return entity;
    }

    void applyDomain(IndicatorValue value) {
        this.value = value.value();
        this.source = value.source();
        this.syncedAt = value.syncedAt() != null ? value.syncedAt() : Instant.now();
    }

    IndicatorValue toDomain() {
        return new IndicatorValue(id, indicatorCode, observedDate, value, source, syncedAt);
    }
}
