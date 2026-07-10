package github.lms.lemuel.ai.chat.adapter.out.llm;

import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.MessageRole;
import github.lms.lemuel.ai.config.AiChatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AnthropicChatAdapter} 예외 변환 단위 검증 — 실 API/네트워크 없이 모델만 목으로 주입한다
 * (패키지-프라이빗 테스트 생성자). complete() 분기(정상/빈응답/하위예외 래핑)를 직접 실행한다.
 *
 * <p>이 테스트는 adapter.out.llm 안에 있고 Spring AI 타입을 import 하지만, ArchUnit 격리 규칙은
 * 테스트를 스캔에서 제외({@code DO_NOT_INCLUDE_TESTS})하므로 위반이 아니다.
 */
class AnthropicChatAdapterTest {

    private final AiChatProperties properties =
            new AiChatProperties("test-key", "claude-test", 1024, 10, 30, "sys");

    @Test
    @DisplayName("정상 텍스트 응답 — ChatCompletion 으로 변환한다")
    void complete_ok() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("정산 주기는 등급별로 다릅니다."));
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(properties, model);

        ChatCompletion completion = adapter.complete("sys", List.of(), "정산 주기?");

        assertThat(completion.text()).isEqualTo("정산 주기는 등급별로 다릅니다.");
        assertThat(completion.model()).isEqualTo("claude-test");
    }

    @Test
    @DisplayName("빈 텍스트 응답 — AiUnavailableException(503)")
    void complete_emptyResponse_throws() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText(""));
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(properties, model);

        assertThatThrownBy(() -> adapter.complete("sys", List.of(), "질문"))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("하위 런타임 예외 — AiUnavailableException 으로 래핑(원문은 cause 로만)")
    void complete_underlyingFailure_wrapped() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("upstream 5xx secret_detail"));
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(properties, model);

        assertThatThrownBy(() -> adapter.complete("sys", List.of(), "질문"))
                .isInstanceOf(AiUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class)
                // 사용자 노출 메시지(getMessage)에는 하위 원문이 새어나가지 않는다.
                .hasMessageNotContaining("secret_detail");
    }

    @Test
    @DisplayName("이력(USER+ASSISTANT)을 프롬프트에 실어 정상 응답한다")
    void complete_withHistory_ok() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(responseWithText("답변"));
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(properties, model);

        List<ChatMessage> history = List.of(
                ChatMessage.user("이전 질문", Instant.now()),
                new ChatMessage(MessageRole.ASSISTANT, "이전 답변", "claude-test", 1, 1, Instant.now()));
        ChatCompletion completion = adapter.complete("sys", history, "후속 질문");

        assertThat(completion.text()).isEqualTo("답변");
    }

    @Test
    @DisplayName("키 미설정(public 생성자) — isConfigured=false, 모델 미조립")
    void notConfigured() {
        AiChatProperties blank = new AiChatProperties("", "claude-test", 1024, 10, 30, "sys");
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(blank);
        assertThat(adapter.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("키 설정(public 생성자) — SDK 클라이언트/모델을 조립해 isConfigured=true (네트워크 미호출)")
    void configured_assemblesModel() {
        AiChatProperties keyed = new AiChatProperties("sk-ant-test", "claude-test", 1024, 10, 30, "sys");
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(keyed);
        assertThat(adapter.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("stream — delta 를 onDelta 로 흘리고 usage 를 집계해 ChatCompletion 을 만든다")
    void stream_ok() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        Usage usage = mock(Usage.class);
        when(usage.getTotalTokens()).thenReturn(120);
        when(usage.getPromptTokens()).thenReturn(100);
        when(usage.getCompletionTokens()).thenReturn(20);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                responseWithText("조각1"),
                responseWithTextAndUsage("조각2", usage)));
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(properties, model);

        List<String> deltas = new ArrayList<>();
        ChatCompletion completion = adapter.stream("sys", List.of(), "질문", deltas::add);

        assertThat(deltas).containsExactly("조각1", "조각2");
        assertThat(completion.text()).isEqualTo("조각1조각2");
        assertThat(completion.inputTokens()).isEqualTo(100);
        assertThat(completion.outputTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("stream — 텍스트 없는 응답만 오면 빈 응답으로 AiUnavailableException")
    void stream_emptyResponse_throws() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(responseWithText("")));
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(properties, model);

        assertThatThrownBy(() -> adapter.stream("sys", List.of(), "질문", d -> { }))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("stream — 하위 스트림 실패는 AiUnavailableException 으로 래핑")
    void stream_underlyingFailure_wrapped() {
        AnthropicChatModel model = mock(AnthropicChatModel.class);
        when(model.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("upstream secret_detail")));
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(properties, model);

        assertThatThrownBy(() -> adapter.stream("sys", List.of(), "질문", d -> { }))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageNotContaining("secret_detail");
    }

    // Spring AI 응답 타입은 final 메서드가 많아 목이 어렵다 — 실제 객체로 조립한다(생성자 확인됨).
    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse responseWithTextAndUsage(String text, Usage usage) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(usage).build());
    }
}
