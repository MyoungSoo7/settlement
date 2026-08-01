package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataReputationProjectionRepository
        extends JpaRepository<ReputationProjectionJpaEntity, String> {
}
