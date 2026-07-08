package github.lms.lemuel.ai.chat.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessageTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");

    @Test
    @DisplayName("user — 사용자 메시지는 모델·토큰 스냅샷이 없다")
    void userMessage() {
        ChatMessage message = ChatMessage.user("안녕하세요", NOW);

        assertThat(message.role()).isEqualTo(MessageRole.USER);
        assertThat(message.model()).isNull();
        assertThat(message.inputTokens()).isNull();
    }

    @Test
    @DisplayName("assistant — LLM 응답의 모델·usage 를 스냅샷한다")
    void assistantMessage() {
        ChatCompletion completion = new ChatCompletion("네, 안녕하세요!", "claude-opus-4-8", 120, 30);

        ChatMessage message = ChatMessage.assistant(completion, NOW);

        assertThat(message.role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(message.content()).isEqualTo("네, 안녕하세요!");
        assertThat(message.model()).isEqualTo("claude-opus-4-8");
        assertThat(message.inputTokens()).isEqualTo(120);
        assertThat(message.outputTokens()).isEqualTo(30);
    }

    @Test
    @DisplayName("빈 내용은 거부한다 — 메시지·완성 모두")
    void rejectsBlank() {
        assertThatThrownBy(() -> ChatMessage.user(" ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatCompletion(" ", "m", null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
