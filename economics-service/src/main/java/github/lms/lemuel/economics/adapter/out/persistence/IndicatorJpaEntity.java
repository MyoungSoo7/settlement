package github.lms.lemuel.economics.adapter.out.persistence;

import github.lms.lemuel.economics.domain.Indicator;
import github.lms.lemuel.economics.domain.IndicatorCycle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "indicators")
public class IndicatorJpaEntity {

    @Id
    @Column(name = "code", length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit;

    // length=1 STRING enum 은 Hibernate 가 CHAR(1) 로 매핑하나 V1 DDL 은 VARCHAR(1) —
    // JDBC 타입을 VARCHAR 로 고정해 ddl-auto=validate 를 통과시킨다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cycle", nullable = false, length = 1)
    private IndicatorCycle cycle;

    @Column(name = "ecos_stat_code", nullable = false, length = 20)
    private String ecosStatCode;

    @Column(name = "ecos_item_code", nullable = false, length = 20)
    private String ecosItemCode;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IndicatorJpaEntity() {
    }

    static IndicatorJpaEntity fromDomain(Indicator indicator) {
        IndicatorJpaEntity entity = new IndicatorJpaEntity();
        entity.code = indicator.code();
        entity.applyDomain(indicator);
        return entity;
    }

    void applyDomain(Indicator indicator) {
        this.name = indicator.name();
        this.unit = indicator.unit();
        this.cycle = indicator.cycle();
        this.ecosStatCode = indicator.ecosStatCode();
        this.ecosItemCode = indicator.ecosItemCode();
        this.updatedAt = indicator.updatedAt() != null ? indicator.updatedAt() : Instant.now();
    }

    Indicator toDomain() {
        return new Indicator(code, name, unit, cycle, ecosStatCode, ecosItemCode, updatedAt);
    }
}
