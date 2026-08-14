package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.domain.DepositProofStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataDepositProofRepository extends JpaRepository<DepositProofJpaEntity, Long> {

    Optional<DepositProofJpaEntity> findBySellerIdAndReferenceTypeAndReferenceIdAndFileHash(
            Long sellerId, String referenceType, String referenceId, String fileHash);

    Optional<DepositProofJpaEntity> findFirstBySellerIdAndReferenceTypeAndReferenceIdOrderByCreatedAtDescIdDesc(
            Long sellerId, String referenceType, String referenceId);

    /** 리뷰 큐 — 최신 우선 (settlement tax 스캔 큐 선례). */
    List<DepositProofJpaEntity> findByStatusOrderByIdDesc(DepositProofStatus status, Pageable pageable);
}
