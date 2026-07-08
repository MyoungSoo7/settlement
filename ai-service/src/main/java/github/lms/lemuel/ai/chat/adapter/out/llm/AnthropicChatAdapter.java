package github.lms.lemuel.ai.chat.adapter.out.llm;

import com.anthropic.client.AnthropicClient;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.application.port.out.ChatCompletionPort;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.MessageRole;
import github.lms.lemuel.ai.config.AiChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSetup;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Spring AI 2.0 (공식 Anthropic Java SDK 기반) {@link ChatCompletionPort} 구현.
 *
 * <p>★ Spring AI/Anthropic SDK 타입은 이 패키지({@code adapter.out.llm}) 밖으로 새지 않는다
 * — ArchUnit 강제. starter 자동설정 대신 여기서 직접 조립한다: 키 미설정 시 모델을 만들지
 * 않아 부팅이 실패하지 않고, {@link #isConfigured()} 가 false 를 돌려 채팅만 503 이 된다.
 *
 * <p>장애 처리(설계 §2.4): 타임아웃·재시도(1회)는 {@link AnthropicSetup#setupSyncClient SDK
 * 클라이언트 레벨}에서 처리하고, 그래도 실패하면 {@link AiUnavailableException} — 자유 대화는
 * 룰 폴백이 불가능하므로 명시적 실패가 정답이다.
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "anthropic")
public class AnthropicChatAdapter implements ChatCompletionPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChatAdapter.class);

    private static final int MAX_RETRIES = 1;

    private final AiChatProperties properties;
    private final AnthropicChatModel chatModel;   // 키 미설정 시 null

    public AnthropicChatAdapter(AiChatProperties properties) {
        this.properties = properties;
        if (properties.configured()) {
            AnthropicClient client = AnthropicSetup.setupSyncClient(
                    null,                                              // baseUrl — SDK 기본(api.anthropic.com)
                    properties.apiKey(),
                    Duration.ofSeconds(properties.timeoutSeconds()),
                    MAX_RETRIES,
                    null, null);
            AnthropicChatOptions.Builder options = AnthropicChatOptions.builder();
            options.model(properties.model());
            options.maxTokens(properties.maxTokens());
            this.chatModel = AnthropicChatModel.builder()
                    .anthropicClient(client)
                    .options(options.build())
                    .build();
        } else {
            this.chatModel = null;
            log.warn("ANTHROPIC_API_KEY 미설정 — 채팅 API 는 503(AI 미구성)으로 응답합니다. 이력 조회는 정상 동작.");
        }
    }

    /** 테스트 전용 — 이미 조립된 모델을 주입한다(실 API 미호출). 프로덕션은 위 public 생성자만 사용. */
    AnthropicChatAdapter(AiChatProperties properties, AnthropicChatModel chatModel) {
        this.properties = properties;
        this.chatModel = chatModel;
    }

    @Override
    public boolean isConfigured() {
        return chatModel != null;
    }

    @Override
    public ChatCompletion complete(String systemPrompt, List<ChatMessage> history, String userMessage) {
        Prompt prompt = buildPrompt(systemPrompt, history, userMessage);
        try {
            return toCompletion(chatModel.call(prompt));
        } catch (AiUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AiUnavailableException("AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    @Override
    public ChatCompletion stream(String systemPrompt, List<ChatMessage> history, String userMessage,
                                 Consumer<String> onDelta) {
        Prompt prompt = buildPrompt(systemPrompt, history, userMessage);
        StringBuilder text = new StringBuilder();
        Usage[] lastUsage = new Usage[1];
        try {
            chatModel.stream(prompt)
                    .doOnNext(response -> {
                        String delta = extractText(response);
                        if (delta != null && !delta.isEmpty()) {
                            text.append(delta);
                            onDelta.accept(delta);
                        }
                        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
                        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
                            lastUsage[0] = usage;
                        }
                    })
                    .blockLast();
        } catch (AiUnavailableException | UncheckedIOException e) {
            // 빈 응답 등 이미 분류된 LLM 실패, 그리고 클라이언트 이탈(onDelta 유래)은
            // 그대로 위임한다 — 정상 이탈을 "LLM 실패"로 오분류하지 않기 위함.
            throw e;
        } catch (RuntimeException e) {
            throw new AiUnavailableException("AI 스트리밍 응답에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        if (text.isEmpty()) {
            throw new AiUnavailableException("AI 가 빈 응답을 반환했습니다.", null);
        }
        Usage usage = lastUsage[0];
        return new ChatCompletion(text.toString(), properties.model(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    private Prompt buildPrompt(String systemPrompt, List<ChatMessage> history, String userMessage) {
        List<Message> messages = new ArrayList<>(history.size() + 2);
        messages.add(new SystemMessage(systemPrompt));
        for (ChatMessage past : history) {
            messages.add(past.role() == MessageRole.USER
                    ? new UserMessage(past.content())
                    : new AssistantMessage(past.content()));
        }
        messages.add(new UserMessage(userMessage));
        return new Prompt(messages);
    }

    private ChatCompletion toCompletion(ChatResponse response) {
        String text = extractText(response);
        if (text == null || text.isBlank()) {
            throw new AiUnavailableException("AI 가 빈 응답을 반환했습니다.", null);
        }
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new ChatCompletion(text, properties.model(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }
}
