package github.lms.lemuel.ai.chat.application.service;

import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase;
import github.lms.lemuel.ai.chat.application.port.out.ChatCompletionPort;
import github.lms.lemuel.ai.chat.application.port.out.LoadConversationPort;
import github.lms.lemuel.ai.chat.application.port.out.RateLimitPort;
import github.lms.lemuel.ai.chat.application.port.out.RetrieveContextPort;
import github.lms.lemuel.ai.chat.application.port.out.SaveConversationPort;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;
import github.lms.lemuel.ai.chat.domain.KnowledgePrompt;
import github.lms.lemuel.ai.chat.domain.PiiMasker;
import github.lms.lemuel.ai.chat.domain.RetrievedContext;
import github.lms.lemuel.ai.config.AiChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><b>RAG(근거 검색) 실패 정책은 LLM 실패 정책과 다르다.</b> LLM 이 죽으면 답이 없으므로
 * 503 으로 올리지만, 검색이 죽으면 <i>근거 없는 답</i>은 여전히 가능하다 — 그래서 검색 실패는
 * 경고 로그를 남기고 근거 0건으로 강등된다(= Phase 1 챗봇으로 되돌아간다). 지식베이스라는
 * 부가 기능의 장애가 챗봇 전체를 멈추게 두지 않겠다는 뜻이다.
 */
@Service
public class ChatService implements ChatUseCase {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatCompletionPort chatCompletionPort;
    private final LoadConversationPort loadConversationPort;
    private final SaveConversationPort saveConversationPort;
    private final RateLimitPort rateLimitPort;
    private final RetrieveContextPort retrieveContextPort;
    private final AiChatProperties properties;
    private final Clock clock;

    public ChatService(ChatCompletionPort chatCompletionPort,
                       LoadConversationPort loadConversationPort,
                       SaveConversationPort saveConversationPort,
                       RateLimitPort rateLimitPort,
                       RetrieveContextPort retrieveContextPort,
                       AiChatProperties properties,
                       Clock clock) {
        this.chatCompletionPort = chatCompletionPort;
        this.loadConversationPort = loadConversationPort;
        this.saveConversationPort = saveConversationPort;
        this.rateLimitPort = rateLimitPort;
        this.retrieveContextPort = retrieveContextPort;
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

        // RAG — 마스킹된 질문으로 지식베이스를 조회한다(원문이 아니라 마스킹본을 쓰는 이유:
        // 검색어도 외부 임베딩 API 로 나가는 egress 경로이기 때문).
        // 근거 0건이면 systemPrompt 는 바이트 그대로 유지된다 → RAG 이전 동작과 동일.
        String systemPrompt = KnowledgePrompt.augment(properties.systemPrompt(), retrieveContext(userMessageText));

        ChatCompletion completion;
        try {
            completion = onDelta == null
                    ? chatCompletionPort.complete(systemPrompt, history, userMessageText)
                    : chatCompletionPort.stream(systemPrompt, history, userMessageText, onDelta);
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

    /**
     * 근거 검색 — 어떤 실패도 대화를 중단시키지 않는다.
     *
     * <p>{@link RetrieveContextPort} 는 계약상 예외를 던지지 않지만, 여기서 한 번 더 막는다.
     * 구현체가 임베딩 API·DB 를 건드리는 이상 계약 위반은 언제든 가능하고, 그때 터지는 자리가
     * 하필 "이미 rate limit 토큰을 소비한 뒤"라서 사용자 입장에선 토큰만 날리고 답도 못 받는
     * 최악의 조합이 된다.
     */
    private List<RetrievedContext> retrieveContext(String userMessageText) {
        try {
            return retrieveContextPort.retrieve(userMessageText);
        } catch (RuntimeException e) {
            log.warn("[RAG] 근거 검색 실패 — 근거 없이 답변합니다: {}", e.toString());
            return List.of();
        }
    }
}
