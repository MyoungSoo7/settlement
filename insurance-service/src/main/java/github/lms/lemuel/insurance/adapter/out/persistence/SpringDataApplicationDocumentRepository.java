package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.ApplicationDocumentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataApplicationDocumentRepository
        extends JpaRepository<ApplicationDocumentJpaEntity, Long> {

    Optional<ApplicationDocumentJpaEntity> findByApplicationIdAndFileHash(UUID applicationId, String fileHash);

    /** 리뷰 큐 — 최신 우선 (settlement tax 스캔 큐 선례). */
    List<ApplicationDocumentJpaEntity> findByStatusOrderByIdDesc(ApplicationDocumentStatus status, Pageable pageable);

    Optional<ApplicationDocumentJpaEntity> findFirstByApplicationIdOrderByCreatedAtDescIdDesc(UUID applicationId);

    /** 청약 접수 시각 — 도메인이 아니라 DB(DEFAULT NOW())가 소유하는 값이라 프로젝션으로만 읽는다. */
    @Query("select a.submittedAt from InsuranceApplicationJpaEntity a where a.applicationId = :applicationId")
    Optional<Instant> findApplicationSubmittedAt(@Param("applicationId") UUID applicationId);
}
