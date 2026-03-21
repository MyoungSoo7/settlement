package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.adapter.out.persistence.entity.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConversationJpaRepository extends JpaRepository<ConversationEntity, Long> {
    List<ConversationEntity> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
