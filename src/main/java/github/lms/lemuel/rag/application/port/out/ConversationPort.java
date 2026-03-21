package github.lms.lemuel.rag.application.port.out;

import github.lms.lemuel.rag.domain.Conversation;

public interface ConversationPort {
    Conversation getConversation(String sessionId);
    void saveMessage(String sessionId, String role, String content);
}
