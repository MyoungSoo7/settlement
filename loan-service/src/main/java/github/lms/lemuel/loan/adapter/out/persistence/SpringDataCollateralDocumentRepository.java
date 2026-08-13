package github.lms.lemuel.loan.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataCollateralDocumentRepository
        extends JpaRepository<CollateralDocumentJpaEntity, Long> {

    Optional<CollateralDocumentJpaEntity> findBySecuredLoanIdAndFileHash(Long securedLoanId, String fileHash);

    Optional<CollateralDocumentJpaEntity> findFirstBySecuredLoanIdOrderByCreatedAtDescIdDesc(Long securedLoanId);
}
