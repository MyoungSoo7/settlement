package github.lms.lemuel.ai.chat.application.service;

import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.exception.RateLimitExceededException;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatCommand;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatResult;
import github.lms.lemuel.ai.chat.application.port.out.ChatCompletionPort;
import github.lms.lemuel.ai.chat.application.port.out.LoadConversationPort;
import github.lms.lemuel.ai.chat.application.port.out.RateLimitPort;
import github.lms.lemuel.ai.chat.application.port.out.SaveConversationPort;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.Conversation;
import github.lms.lemuel.ai.chat.domain.MessageRole;
import github.lms.lemuel.ai.config.AiChatProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-08T12:00:00Z");
    private static final Long USER_ID = 42L;
    private static final String SYSTEM_PROMPT = "당신은 Lemuel 도우미입니다.";

    @Mock ChatCompletionPort chatCompletionPort;
    @Mock LoadConversationPort loadConversationPort;
    @Mock SaveConversationPort saveConversationPort;
    @Mock RateLimitPort rateLimitPort;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        AiChatProperties properties = new AiChatProperties(
                "test-key", "claude-test", 1024, 10, 30, SYSTEM_PROMPT);
        chatService = new ChatService(chatCompletionPort, loadConversationPort, saveConversationPort,
                rateLimitPort, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("신규 대화 — 빈 히스토리로 LLM 호출 후 왕복 2건이 단일 저장으로 남는다")
    void chat_newConversation() {
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        when(chatCompletionPort.complete(eq(SYSTEM_PROMPT), eq(List.of()), eq("안녕하세요")))
                .thenReturn(new ChatCompletion("네, 무엇을 도와드릴까요?", "claude-test", 100, 20));

        ChatResult result = chatService.chat(new ChatCommand(USER_ID, null, "안녕하세요"));

        assertThat(result.conversationId()).isNotNull();
        assertThat(result.reply()).isEqualTo("네, 무엇을 도와드릴까요?");
        assertThat(result.inputTokens()).isEqualTo(100);

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        ArgumentCaptor<ChatMessage> userMsg = ArgumentCaptor.forClass(ChatMessage.class);
        ArgumentCaptor<ChatMessage> assistantMsg = ArgumentCaptor.forClass(ChatMessage.class);
        verify(saveConversationPort).saveExchange(saved.capture(), userMsg.capture(), assistantMsg.capture());
        assertThat(saved.getValue().messageCount()).isEqualTo(2);
        assertThat(saved.getValue().title()).isEqualTo("안녕하세요");
        assertThat(userMsg.getValue().role()).isEqualTo(MessageRole.USER);
        assertThat(assistantMsg.getValue().role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(assistantMsg.getValue().outputTokens()).isEqualTo(20);
    }

    @Test
    @DisplayName("기존 대화 — 최근 히스토리 윈도(10)를 실어 LLM 을 호출한다")
    void chat_existingConversation_usesHistoryWindow() {
        UUID conversationId = UUID.randomUUID();
        Conversation existing = Conversation.restore(conversationId, USER_ID, "제목", 4, NOW, NOW);
        List<ChatMessage> history = List.of(
                ChatMessage.user("이전 질문", NOW),
                new ChatMessage(MessageRole.ASSISTANT, "이전 답변", "claude-test", 10, 5, NOW));
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        when(loadConversationPort.findOwned(conversationId, USER_ID)).thenReturn(Optional.of(existing));
        when(loadConversationPort.findRecentMessages(conversationId, 10)).thenReturn(history);
        when(chatCompletionPort.complete(SYSTEM_PROMPT, history, "후속 질문"))
                .thenReturn(new ChatCompletion("후속 답변", "claude-test", 200, 40));

        ChatResult result = chatService.chat(new ChatCommand(USER_ID, conversationId, "후속 질문"));

        assertThat(result.conversationId()).isEqualTo(conversationId);
        verify(chatCompletionPort).complete(SYSTEM_PROMPT, history, "후속 질문");
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(saveConversationPort).saveExchange(saved.capture(), any(), any());
        assertThat(saved.getValue().messageCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("타인 대화 또는 없는 대화 — 404 예외, LLM 은 호출되지 않는다")
    void chat_notOwned() {
        UUID conversationId = UUID.randomUUID();
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        when(loadConversationPort.findOwned(conversationId, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.chat(new ChatCommand(USER_ID, conversationId, "질문")))
                .isInstanceOf(ConversationNotFoundException.class);

        verify(chatCompletionPort, never()).complete(anyString(), any(), anyString());
        verifyNoInteractions(saveConversationPort);
    }

    @Test
    @DisplayName("API 키 미구성 — 503 예외, rate limit 도 소비하지 않는다")
    void chat_notConfigured() {
        when(chatCompletionPort.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> chatService.chat(new ChatCommand(USER_ID, null, "질문")))
                .isInstanceOf(AiNotConfiguredException.class);

        verifyNoInteractions(rateLimitPort, saveConversationPort);
    }

    @Test
    @DisplayName("rate limit 초과 — LLM 호출 전에 차단된다 (비용 가드)")
    void chat_rateLimited() {
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        doThrow(new RateLimitExceededException(30)).when(rateLimitPort).acquire(USER_ID);

        assertThatThrownBy(() -> chatService.chat(new ChatCommand(USER_ID, null, "질문")))
                .isInstanceOf(RateLimitExceededException.class);

        verify(chatCompletionPort, never()).complete(anyString(), any(), anyString());
        verifyNoInteractions(saveConversationPort);
    }

    @Test
    @DisplayName("LLM 실패 — 예외가 그대로 나가고, 저장 없음 + 소비한 rate limit 토큰은 환불된다 (§2.4, C-L2)")
    void chat_llmFailure_nothingSaved_andRefunds() {
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        when(chatCompletionPort.complete(anyString(), any(), anyString()))
                .thenThrow(new AiUnavailableException("실패", null));

        assertThatThrownBy(() -> chatService.chat(new ChatCommand(USER_ID, null, "질문")))
                .isInstanceOf(AiUnavailableException.class);

        verifyNoInteractions(saveConversationPort);
        // 과금 없이 실패했으므로 acquire 로 소비한 토큰을 되돌려야 한다(장애 중 쿼터 소진 방지).
        verify(rateLimitPort).acquire(USER_ID);
        verify(rateLimitPort).refund(USER_ID);
    }

    @Test
    @DisplayName("스트리밍 — onDelta 가 있으면 stream 경로를 타고 동일하게 저장된다")
    void chat_streaming() {
        StringBuilder received = new StringBuilder();
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        when(chatCompletionPort.stream(eq(SYSTEM_PROMPT), eq(List.of()), eq("질문"), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Consumer<String> onDelta = invocation.getArgument(3);
                    onDelta.accept("답");
                    onDelta.accept("변");
                    return new ChatCompletion("답변", "claude-test", 50, 10);
                });

        ChatResult result = chatService.chat(new ChatCommand(USER_ID, null, "질문"), received::append);

        assertThat(received.toString()).isEqualTo("답변");
        assertThat(result.reply()).isEqualTo("답변");
        verify(saveConversationPort).saveExchange(any(), any(), any());
        verify(chatCompletionPort, never()).complete(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("커맨드 검증 — userId/message 누락은 생성 시점에 거부된다")
    void command_validation() {
        assertThatThrownBy(() -> new ChatCommand(null, null, "질문"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChatCommand(USER_ID, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
