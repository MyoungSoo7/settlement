package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.ProposalStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataProposalQuoteRepository
        extends JpaRepository<ProposalQuoteJpaEntity, Long> {

    Optional<ProposalQuoteJpaEntity> findByProposalId(UUID proposalId);

    /** 만기 스윕 스캔 — idx_proposal_expiry_scan (partial index, status=QUOTED) 을 탄다. */
    List<ProposalQuoteJpaEntity> findByStatusAndValidUntilBefore(ProposalStatus status, LocalDate date);
}
