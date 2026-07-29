package github.lms.lemuel.loan.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CollateralRevaluationRepository
        extends JpaRepository<CollateralRevaluationJpaEntity, Long> {

    /** 최신 재평가 1건 — idx_collateral_reval_latest 가 커버한다. */
    Optional<CollateralRevaluationJpaEntity>
            findFirstByCollateralIdOrderByRevaluedAtDescIdDesc(Long collateralId);
}
