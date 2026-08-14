package github.lms.lemuel.deposit.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataDepositProofRepository extends JpaRepository<DepositProofJpaEntity, Long> {

    Optional<DepositProofJpaEntity> findBySellerIdAndReferenceTypeAndReferenceIdAndFileHash(
            Long sellerId, String referenceType, String referenceId, String fileHash);

    Optional<DepositProofJpaEntity> findFirstBySellerIdAndReferenceTypeAndReferenceIdOrderByCreatedAtDescIdDesc(
            Long sellerId, String referenceType, String referenceId);
}
