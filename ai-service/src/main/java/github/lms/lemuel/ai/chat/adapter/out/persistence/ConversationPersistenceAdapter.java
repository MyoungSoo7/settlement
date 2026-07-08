package github.lms.lemuel.ai.chat.adapter.out.persistence;

import github.lms.lemuel.ai.chat.application.port.out.LoadConversationPort;
import github.lms.lemuel.ai.chat.application.port.out.SaveConversationPort;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 대화 영속성 어댑터 — lemuel_ai 의 chat_conversations / chat_messages.
 *
 * <p>{@link #saveExchange} 가 단일 트랜잭션 경계다: LLM 호출 성공 후에만 불리므로
 * 실패한 왕복은 이력에 흔적을 남기지 않는다(설계 §2.4).
 */
@Component
public class ConversationPersistenceAdapter implements LoadConversationPort, SaveConversationPort {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;

    public ConversationPersistenceAdapter(ConversationRepository conversationRepository,
                                          ChatMessageRepository chatMessageRepository) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Conversation> findOwned(UUID conversationId, Long userId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .map(ConversationJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> findRecentMessages(UUID conversationId, int limit) {
        List<ChatMessageJpaEntity> newestFirst =
                chatMessageRepository.findByConversationIdOrderByIdDesc(conversationId, PageRequest.of(0, limit));
        List<ChatMessage> oldestFirst = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            oldestFirst.add(newestFirst.get(i).toDomain());
        }
        return oldestFirst;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> findAllMessages(UUID conversationId) {
        return chatMessageRepository.findByConversationIdOrderByIdAsc(conversationId).stream()
                .map(ChatMessageJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Conversation> listByUser(Long userId, int page, int size) {
        return conversationRepository
                .findByUserIdOrderByLastMessageAtDesc(userId, PageRequest.of(page, size))
                .map(ConversationJpaEntity::toDomain)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByUser(Long userId) {
        return conversationRepository.countByUserId(userId);
    }

    @Override
    @Transactional
    public void saveExchange(Conversation conversation, ChatMessage userMessage, ChatMessage assistantMessage) {
        UUID conversationId = conversation.id();
        // 한 왕복 = 메시지 2건(user+assistant). 기존 대화는 message_count 를 원자적으로 +2 하여
        // 동시 왕복 시 로스트 업데이트를 차단한다. 매칭 행이 없으면(0) 신규 대화이므로 INSERT.
        int updated = conversationRepository.incrementExchange(
                conversationId, 2, conversation.lastMessageAt(), Instant.now());
        if (updated == 0) {
            conversationRepository.save(ConversationJpaEntity.from(conversation));
        }
        chatMessageRepository.save(ChatMessageJpaEntity.from(conversationId, userMessage));
        chatMessageRepository.save(ChatMessageJpaEntity.from(conversationId, assistantMessage));
    }

    @Override
    @Transactional
    public boolean delete(UUID conversationId, Long userId) {
        // 메시지는 chat_messages.conversation_id FK 의 ON DELETE CASCADE 가 정리한다.
        return conversationRepository.deleteByIdAndUserId(conversationId, userId) > 0;
    }
}
