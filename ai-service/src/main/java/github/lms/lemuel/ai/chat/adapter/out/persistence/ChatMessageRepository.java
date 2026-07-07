package github.lms.lemuel.ai.chat.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository extends JpaRepository<ChatMessageJpaEntity, Long> {

    List<ChatMessageJpaEntity> findByConversationIdOrderByIdAsc(UUID conversationId);

    /** 최신 메시지 N개 (id 내림차순) — 컨텍스트 윈도용, 호출측에서 뒤집어 시간순으로 쓴다. */
    List<ChatMessageJpaEntity> findByConversationIdOrderByIdDesc(UUID conversationId, Pageable pageable);
}
