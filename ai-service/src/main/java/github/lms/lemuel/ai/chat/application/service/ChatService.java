package github.lms.lemuel.ai.chat.application.service;

import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase;
import github.lms.lemuel.ai.chat.application.port.out.ChatCompletionPort;
import github.lms.lemuel.ai.chat.application.port.out.LoadConversationPort;
import github.lms.lemuel.ai.chat.application.port.out.RateLimitPort;
import github.lms.lemuel.ai.chat.application.port.out.SaveConversationPort;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;
import github.lms.lemuel.ai.chat.domain.PiiMasker;
import github.lms.lemuel.ai.config.AiChatProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * 챗봇 대화 1왕복 유스케이스 구현 (설계 §8 시퀀스).
 *
 * <p>트랜잭션 경계 주의: LLM 호출(최대 30s)은 <b>트랜잭션 밖</b>에서 수행한다 — DB 커넥션을
 * 붙든 채 외부 API 를 기다리면 풀이 고갈된다. 조회(무tx) → LLM 호출(무tx) →
 * 저장({@code SaveConversationPort.saveExchange} 가 단일 tx)의 3단으로 나눠,
 * LLM 실패 시 아무것도 저장되지 않는 원칙(§2.4)이 트랜잭션 구조에서 자연히 성립한다.
 */
@Service
public class ChatService implements ChatUseCase {

    private final ChatCompletionPort chatCompletionPort;
    private final LoadConversationPort loadConversationPort;
    private final SaveConversationPort saveConversationPort;
    private final RateLimitPort rateLimitPort;
    private final AiChatProperties properties;
    private final Clock clock;

    public ChatService(ChatCompletionPort chatCompletionPort,
                       LoadConversationPort loadConversationPort,
                       SaveConversationPort saveConversationPort,
                       RateLimitPort rateLimitPort,
                       AiChatProperties properties,
                       Clock clock) {
        this.chatCompletionPort = chatCompletionPort;
        this.loadConversationPort = loadConversationPort;
        this.saveConversationPort = saveConversationPort;
        this.rateLimitPort = rateLimitPort;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ChatResult chat(ChatCommand command) {
        return chat(command, null);
    }

    @Override
    public ChatResult chat(ChatCommand command, Consumer<String> onDelta) {
        if (!chatCompletionPort.isConfigured()) {
            throw new AiNotConfiguredException();
        }
        rateLimitPort.acquire(command.userId());

        // PII 마스킹 — 저장(content/title)·외부 LLM 전송 전 단일 초크포인트.
        // 이후 command.message() 원문 대신 마스킹본만 사용한다(카드번호·주민번호 유출 차단).
        String userMessageText = PiiMasker.mask(command.message());

        Conversation conversation;
        List<ChatMessage> history;
        if (command.conversationId() != null) {
            conversation = loadConversationPort.findOwned(command.conversationId(), command.userId())
                    .orElseThrow(() -> new ConversationNotFoundException(command.conversationId()));
            history = loadConversationPort.findRecentMessages(conversation.id(), properties.historyWindow());
        } else {
            conversation = Conversation.start(command.userId(), userMessageText, clock.instant());
            history = List.of();
        }

        ChatCompletion completion;
        try {
            completion = onDelta == null
                    ? chatCompletionPort.complete(properties.systemPrompt(), history, userMessageText)
                    : chatCompletionPort.stream(properties.systemPrompt(), history, userMessageText, onDelta);
        } catch (AiUnavailableException e) {
            // LLM 이 과금 없이 실패(빈 응답·5xx·타임아웃) — 소비한 rate limit 토큰을 되돌린다.
            // (스트리밍 중 클라이언트 이탈은 UncheckedIOException 이라 여기 걸리지 않음: 이미 과금됨 → 환불 안 함)
            rateLimitPort.refund(command.userId());
            throw e;
        }

        Instant now = clock.instant();
        ChatMessage userMessage = ChatMessage.user(userMessageText, now);
        ChatMessage assistantMessage = ChatMessage.assistant(completion, now);
        conversation.recordExchange(now);
        saveConversationPort.saveExchange(conversation, userMessage, assistantMessage);

        return new ChatResult(conversation.id(), completion.text(), completion.model(),
                completion.inputTokens(), completion.outputTokens());
    }
}
