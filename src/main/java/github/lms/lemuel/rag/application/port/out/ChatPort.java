package github.lms.lemuel.rag.application.port.out;

import java.util.List;
import java.util.function.Consumer;

public interface ChatPort {
    record ChatMessage(String role, String content) {}

    void streamChat(String systemPrompt, List<ChatMessage> messages, Consumer<String> tokenConsumer);
}
