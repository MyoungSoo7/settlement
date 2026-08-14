package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.domain.CollateralDocumentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataCollateralDocumentRepository
        extends JpaRepository<CollateralDocumentJpaEntity, Long> {

    Optional<CollateralDocumentJpaEntity> findBySecuredLoanIdAndFileHash(Long securedLoanId, String fileHash);

    Optional<CollateralDocumentJpaEntity> findFirstBySecuredLoanIdOrderByCreatedAtDescIdDesc(Long securedLoanId);

    /** 리뷰 큐 — 최신 우선 (settlement tax 스캔 큐 선례). */
    List<CollateralDocumentJpaEntity> findByStatusOrderByIdDesc(CollateralDocumentStatus status, Pageable pageable);
}
