package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.adapter.out.persistence.entity.DocumentEmbeddingEntity;
import github.lms.lemuel.rag.domain.EntityType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentEmbeddingJpaRepository extends JpaRepository<DocumentEmbeddingEntity, Long> {
    Optional<DocumentEmbeddingEntity> findByEntityTypeAndEntityId(EntityType entityType, Long entityId);
    long countByEntityType(EntityType entityType);
}
