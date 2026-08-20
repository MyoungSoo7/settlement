package github.lms.lemuel.ai.chat.adapter.out.llm;

import github.lms.lemuel.ai.chat.application.port.out.ChatCompletionPort;
import github.lms.lemuel.ai.config.AiChatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.ai.provider} 스위치 검증 — <b>정확히 하나만 등록</b>이 이 서비스의 불변식이다.
 *
 * <p>어댑터 단위 테스트는 이 축을 못 덮는다: {@code @ConditionalOnProperty} 의 {@code havingValue}
 * 에 오타가 나면 컴파일도 테스트도 통과하고 <b>런타임에 조용히 0개</b>가 등록돼 채팅 빈이 사라진다.
 * 그래서 컨텍스트 조립 자체를 어서션한다(DB·네트워크 없이 {@link ApplicationContextRunner} 로).
 */
class ChatProviderSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestProps.class,
                    GeminiChatAdapter.class, AnthropicChatAdapter.class, DeepSeekChatAdapter.class);

    @Test
    @DisplayName("provider 미설정 — Gemini 하나만 등록(matchIfMissing 기본값)")
    void missing_defaultsToGemini() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ChatCompletionPort.class)
                .getBean(ChatCompletionPort.class).isInstanceOf(GeminiChatAdapter.class));
    }

    @Test
    @DisplayName("provider=gemini — Gemini 하나만")
    void gemini() {
        runner.withPropertyValues("app.ai.provider=gemini")
                .run(ctx -> assertThat(ctx).hasSingleBean(ChatCompletionPort.class)
                        .getBean(ChatCompletionPort.class).isInstanceOf(GeminiChatAdapter.class));
    }

    @Test
    @DisplayName("provider=anthropic — Anthropic 하나만")
    void anthropic() {
        runner.withPropertyValues("app.ai.provider=anthropic")
                .run(ctx -> assertThat(ctx).hasSingleBean(ChatCompletionPort.class)
                        .getBean(ChatCompletionPort.class).isInstanceOf(AnthropicChatAdapter.class));
    }

    @Test
    @DisplayName("provider=deepseek — DeepSeek 하나만(키 미설정이어도 부팅은 성공)")
    void deepseek() {
        runner.withPropertyValues("app.ai.provider=deepseek")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ChatCompletionPort.class)
                            .getBean(ChatCompletionPort.class).isInstanceOf(DeepSeekChatAdapter.class);
                    assertThat(ctx.getBean(ChatCompletionPort.class).isConfigured()).isFalse();
                });
    }

    @Test
    @DisplayName("provider 오타 — 아무 어댑터도 등록되지 않는다(설정 실수의 실제 증상을 문서화)")
    void unknownProvider_registersNothing() {
        runner.withPropertyValues("app.ai.provider=deepsek")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ChatCompletionPort.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestProps {

        @Bean
        GeminiChatProperties geminiChatProperties() {
            return new GeminiChatProperties("", null, null, 0);
        }

        @Bean
        DeepSeekChatProperties deepSeekChatProperties() {
            return new DeepSeekChatProperties("", null, null, 0);
        }

        @Bean
        AiChatProperties aiChatProperties() {
            return new AiChatProperties("", "claude-opus-4-8", 1024, 10, 30, "system");
        }
    }
}
