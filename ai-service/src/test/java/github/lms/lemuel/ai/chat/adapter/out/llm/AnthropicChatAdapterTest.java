package github.lms.lemuel.ai.chat.adapter.out.llm;

import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.config.AiChatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

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
    @DisplayName("키 미설정(public 생성자) — isConfigured=false, 모델 미조립")
    void notConfigured() {
        AiChatProperties blank = new AiChatProperties("", "claude-test", 1024, 10, 30, "sys");
        AnthropicChatAdapter adapter = new AnthropicChatAdapter(blank);
        assertThat(adapter.isConfigured()).isFalse();
    }

    // Spring AI 응답 타입은 final 메서드가 많아 목이 어렵다 — 실제 객체로 조립한다(생성자 확인됨).
    private static ChatResponse responseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
