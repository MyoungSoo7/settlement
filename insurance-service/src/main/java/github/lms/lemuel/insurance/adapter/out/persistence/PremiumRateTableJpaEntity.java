package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadRateTablePort.RateSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * premium_rate_tables 테이블 매핑 (V9) — 요율 조회 전용.
 *
 * <p>D-P1: 요율 개정은 새 행(effective_from 버저닝) — 이 엔티티로 UPDATE 하지 않는다.
 */
@Entity
@Table(name = "premium_rate_tables", schema = "opslab")
public class PremiumRateTableJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false, length = 32)
    private String productCode;

    @Column(nullable = false, length = 1)
    private String gender;

    @Column(name = "age_from", nullable = false)
    private int ageFrom;

    @Column(name = "age_to", nullable = false)
    private int ageTo;

    @Column(name = "payment_term_years", nullable = false)
    private int paymentTermYears;

    @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
    private BigDecimal ratePerMille;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    protected PremiumRateTableJpaEntity() {
    }

    public RateSnapshot toSnapshot() {
        return new RateSnapshot(id, ratePerMille);
    }
}
