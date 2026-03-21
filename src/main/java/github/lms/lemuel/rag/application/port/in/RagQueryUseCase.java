package github.lms.lemuel.rag.application.port.in;

import java.util.function.Consumer;

public interface RagQueryUseCase {
    void query(String sessionId, String question, Consumer<String> tokenConsumer);
}
