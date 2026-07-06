package github.lms.lemuel.company.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ReputationScoreRepository extends JpaRepository<ReputationScoreJpaEntity, Long> {

    Optional<ReputationScoreJpaEntity> findFirstByStockCodeOrderByCalculatedAtDesc(String stockCode);

    Page<ReputationScoreJpaEntity> findByStockCodeOrderBySnapshotDateDesc(String stockCode, Pageable pageable);

    boolean existsByStockCodeAndSnapshotDate(String stockCode, LocalDate snapshotDate);
}
