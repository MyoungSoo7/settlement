package github.lms.lemuel.ai.chat.adapter.out.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationJpaEntity, UUID> {

    Optional<ConversationJpaEntity> findByIdAndUserId(UUID id, Long userId);

    Page<ConversationJpaEntity> findByUserIdOrderByLastMessageAtDesc(Long userId, Pageable pageable);

    long countByUserId(Long userId);

    long deleteByIdAndUserId(UUID id, Long userId);
}
