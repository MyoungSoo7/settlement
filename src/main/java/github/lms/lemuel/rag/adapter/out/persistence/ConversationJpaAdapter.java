package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.adapter.out.persistence.entity.ConversationEntity;
import github.lms.lemuel.rag.application.port.out.ConversationPort;
import github.lms.lemuel.rag.domain.Conversation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ConversationJpaAdapter implements ConversationPort {

    private final ConversationJpaRepository conversationJpaRepository;

    @Override
    public Conversation getConversation(String sessionId) {
        List<ConversationEntity> entities =
                conversationJpaRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        List<Conversation.Message> messages = entities.stream()
                .map(e -> Conversation.Message.builder()
                        .role(e.getRole())
                        .content(e.getContent())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();

        return Conversation.builder()
                .sessionId(sessionId)
                .messages(messages)
                .build();
    }

    @Override
    public void saveMessage(String sessionId, String role, String content) {
        ConversationEntity entity = ConversationEntity.builder()
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .build();
        conversationJpaRepository.save(entity);
    }
}
