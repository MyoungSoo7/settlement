package github.lms.lemuel.insurance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SpringDataPremiumRateTableRepository
        extends JpaRepository<PremiumRateTableJpaEntity, Long> {

    /**
     * 적용 요율 후보 — 기준일 이전 개시분 중 최신 우선(effective_from DESC).
     * 첫 행이 이긴다 (D-P1 버저닝).
     */
    @Query("""
            SELECT r FROM PremiumRateTableJpaEntity r
            WHERE r.productCode = :productCode
              AND r.gender = :gender
              AND r.paymentTermYears = :paymentTermYears
              AND r.ageFrom <= :insuranceAge
              AND r.ageTo >= :insuranceAge
              AND r.effectiveFrom <= :asOf
            ORDER BY r.effectiveFrom DESC, r.id DESC
            """)
    List<PremiumRateTableJpaEntity> findApplicable(@Param("productCode") String productCode,
                                                   @Param("gender") String gender,
                                                   @Param("insuranceAge") int insuranceAge,
                                                   @Param("paymentTermYears") int paymentTermYears,
                                                   @Param("asOf") LocalDate asOf);
}
