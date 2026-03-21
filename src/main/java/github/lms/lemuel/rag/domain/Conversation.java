package github.lms.lemuel.rag.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class Conversation {
    private final String sessionId;
    private final List<Message> messages;

    @Getter
    @Builder
    public static class Message {
        private final String role;
        private final String content;
        private final LocalDateTime createdAt;
    }
}
