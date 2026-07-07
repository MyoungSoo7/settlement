package github.lms.lemuel.ai.chat.adapter.out.persistence;

import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_messages")
public class ChatMessageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(length = 60)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatMessageJpaEntity() {
    }

    static ChatMessageJpaEntity from(UUID conversationId, ChatMessage message) {
        ChatMessageJpaEntity entity = new ChatMessageJpaEntity();
        entity.conversationId = conversationId;
        entity.role = message.role();
        entity.content = message.content();
        entity.model = message.model();
        entity.inputTokens = message.inputTokens();
        entity.outputTokens = message.outputTokens();
        entity.createdAt = message.createdAt();
        return entity;
    }

    ChatMessage toDomain() {
        return new ChatMessage(role, content, model, inputTokens, outputTokens, createdAt);
    }
}
