package github.lms.lemuel.ai.chat.adapter.out.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GeminiChatAdapter} 응답 파싱 단위 검증 — 실 API 없이 Generative Language API 정본 응답으로.
 * (parse 는 package-private static — 네트워크 없이 분기 전수 검증)
 */
class GeminiChatAdapterTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("정상 응답 — text·usageMetadata 토큰 추출")
    void parse_ok() {
        String json = """
                {"candidates":[{"content":{"parts":[{"text":"정산 주기는 등급별로 다릅니다."}],"role":"model"},
                "finishReason":"STOP","index":0}],
                "usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":42,"totalTokenCount":100},
                "modelVersion":"gemini-2.5-flash"}
                """;
        ChatCompletion c = GeminiChatAdapter.parse(json, "gemini-2.5-flash", om);
        assertThat(c.text()).isEqualTo("정산 주기는 등급별로 다릅니다.");
        assertThat(c.model()).isEqualTo("gemini-2.5-flash");
        assertThat(c.inputTokens()).isEqualTo(6);
        assertThat(c.outputTokens()).isEqualTo(42);
    }

    @Test
    @DisplayName("빈 candidates(안전차단 등) — AiUnavailableException")
    void parse_emptyCandidates_throws() {
        assertThatThrownBy(() -> GeminiChatAdapter.parse(
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", "gemini-2.5-flash", om))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("빈 text(thinking 이 예산 소진, finishReason=MAX_TOKENS) — AiUnavailableException")
    void parse_blankText_throws() {
        String json = """
                {"candidates":[{"content":{"role":"model"},"finishReason":"MAX_TOKENS","index":0}],
                "usageMetadata":{"promptTokenCount":6,"candidatesTokenCount":0,"totalTokenCount":2048}}
                """;
        assertThatThrownBy(() -> GeminiChatAdapter.parse(json, "gemini-2.5-flash", om))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("MAX_TOKENS");
    }

    @Test
    @DisplayName("깨진 JSON — AiUnavailableException(파싱 실패)")
    void parse_malformed_throws() {
        assertThatThrownBy(() -> GeminiChatAdapter.parse("not-json{", "gemini-2.5-flash", om))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("키 미설정 — isConfigured=false, 키 있으면 true")
    void isConfigured() {
        assertThat(new GeminiChatAdapter(new GeminiChatProperties("", null, null, 0)).isConfigured()).isFalse();
        assertThat(new GeminiChatAdapter(new GeminiChatProperties("k", null, null, 0)).isConfigured()).isTrue();
    }
}
