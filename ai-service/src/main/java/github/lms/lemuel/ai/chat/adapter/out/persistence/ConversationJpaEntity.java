package github.lms.lemuel.ai.chat.adapter.out.persistence;

import github.lms.lemuel.ai.chat.domain.Conversation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_conversations")
public class ConversationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationJpaEntity() {
    }

    static ConversationJpaEntity from(Conversation conversation) {
        ConversationJpaEntity entity = new ConversationJpaEntity();
        entity.id = conversation.id();
        entity.userId = conversation.userId();
        entity.title = conversation.title();
        entity.messageCount = conversation.messageCount();
        entity.lastMessageAt = conversation.lastMessageAt();
        entity.createdAt = conversation.createdAt();
        entity.updatedAt = Instant.now();
        return entity;
    }

    void applyExchange(Conversation conversation) {
        this.messageCount = conversation.messageCount();
        this.lastMessageAt = conversation.lastMessageAt();
    }

    Conversation toDomain() {
        return Conversation.restore(id, userId, title, messageCount, lastMessageAt, createdAt);
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }
}
