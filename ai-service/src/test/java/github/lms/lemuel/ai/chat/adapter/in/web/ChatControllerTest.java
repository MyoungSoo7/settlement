package github.lms.lemuel.ai.chat.adapter.in.web;

import github.lms.lemuel.ai.chat.adapter.in.web.dto.ChatDtos.StreamError;
import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.exception.RateLimitExceededException;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatCommand;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatResult;
import github.lms.lemuel.ai.audit.application.port.out.RecordAuditPort;
import github.lms.lemuel.ai.chat.application.port.in.ConversationQuery;
import github.lms.lemuel.ai.config.AiChatProperties;
import github.lms.lemuel.common.config.jwt.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨트롤러 단위 검증 (standalone MockMvc — 보안 401/403 은 통합 테스트에서 검증).
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock ChatUseCase chatUseCase;
    @Mock ConversationQuery conversationQuery;

    private MockMvc mockMvc;

    private final Authentication auth = new UsernamePasswordAuthenticationToken(
            new AuthPrincipal(42L, "user@test.com", "USER"), null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @BeforeEach
    void setUp() {
        AiChatProperties properties = new AiChatProperties("key", "claude-test", 1024, 10, 30, "sys");
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatUseCase, properties),
                        new ConversationController(conversationQuery, mock(RecordAuditPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /api/ai/chat — 200 + 응답 본문, uid 가 커맨드로 전달된다")
    void chat_ok() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(chatUseCase.chat(any(ChatCommand.class)))
                .thenReturn(new ChatResult(conversationId, "답변입니다", "claude-test", 100, 20));

        mockMvc.perform(post("/api/ai/chat")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"안녕하세요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$.reply").value("답변입니다"))
                .andExpect(jsonPath("$.usage.inputTokens").value(100));

        ArgumentCaptor<ChatCommand> command = ArgumentCaptor.forClass(ChatCommand.class);
        org.mockito.Mockito.verify(chatUseCase).chat(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("빈 message — 400")
    void chat_blankMessage() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("uid 없는 구 토큰 — 401 재로그인 유도")
    void chat_legacyTokenWithoutUid() throws Exception {
        Authentication legacy = new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(null, "old@test.com", "USER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        mockMvc.perform(post("/api/ai/chat")
                        .principal(legacy)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"질문\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AI 미구성 — 503 + 안내 메시지")
    void chat_notConfigured() throws Exception {
        when(chatUseCase.chat(any(ChatCommand.class))).thenThrow(new AiNotConfiguredException());

        mockMvc.perform(post("/api/ai/chat")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"질문\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("rate limit 초과 — 429 + Retry-After 헤더")
    void chat_rateLimited() throws Exception {
        when(chatUseCase.chat(any(ChatCommand.class))).thenThrow(new RateLimitExceededException(30));

        mockMvc.perform(post("/api/ai/chat")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"질문\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));
    }

    @Test
    @DisplayName("타인 대화 접근 — 404")
    void chat_conversationNotFound() throws Exception {
        UUID otherId = UUID.randomUUID();
        when(chatUseCase.chat(any(ChatCommand.class))).thenThrow(new ConversationNotFoundException(otherId));

        mockMvc.perform(post("/api/ai/chat")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + otherId + "\",\"message\":\"질문\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/ai/chat/stream — delta 이벤트 반복 후 done 이벤트로 전체 결과 전달")
    void chatStream_deltasThenDone() throws Exception {
        UUID conversationId = UUID.randomUUID();
        // delta 페이로드는 ASCII 로 둔다 — 원시 SSE data 필드는 MockMvc 응답 디코딩에서
        // 멀티바이트가 깨질 수 있어(전송 메커니즘과 무관), 조각 식별은 ASCII 로 검증한다.
        when(chatUseCase.chat(any(ChatCommand.class), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("delta-A");
            onDelta.accept("delta-B");
            return new ChatResult(conversationId, "delta-Adelta-B", "claude-test", 100, 20);
        });

        MvcResult started = mockMvc.perform(post("/api/ai/chat/stream")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"질문\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(5000);   // 가상 스레드가 emitter.complete() 할 때까지 대기

        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("delta-A").contains("delta-B")
                .contains("done").contains(conversationId.toString());
    }

    @Test
    @DisplayName("POST /api/ai/chat/stream — 도중 실패 시 error 이벤트(code+안전 메시지)로 종료")
    void chatStream_failureEmitsErrorEvent() throws Exception {
        when(chatUseCase.chat(any(ChatCommand.class), any()))
                .thenThrow(new AiUnavailableException("AI 응답 생성에 실패했습니다.", null));

        MvcResult started = mockMvc.perform(post("/api/ai/chat/stream")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"질문\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(5000);

        String body = mockMvc.perform(asyncDispatch(started))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("error").contains("AI_UNAVAILABLE");
    }

    @Test
    @DisplayName("SSE 스트림 에러 매핑 — 도메인 예외는 code+안전메시지, 예기치 못한 예외는 원문 미노출(S-H1)")
    void toStreamError_mapsSafely() {
        assertThat(ChatController.toStreamError(new RateLimitExceededException(30)).code())
                .isEqualTo("RATE_LIMITED");
        assertThat(ChatController.toStreamError(new ConversationNotFoundException(UUID.randomUUID())).code())
                .isEqualTo("NOT_FOUND");
        assertThat(ChatController.toStreamError(new AiNotConfiguredException()).code())
                .isEqualTo("AI_NOT_CONFIGURED");
        assertThat(ChatController.toStreamError(new AiUnavailableException("일시적 오류", null)).code())
                .isEqualTo("AI_UNAVAILABLE");

        // 예기치 못한 예외(DB 제약조건명 등)의 원문은 클라이언트로 새어나가면 안 된다.
        StreamError generic = ChatController.toStreamError(
                new IllegalStateException("column secret_col violates unique constraint uk_xyz"));
        assertThat(generic.code()).isEqualTo("ERROR");
        assertThat(generic.message())
                .isEqualTo("AI 응답 처리 중 오류가 발생했습니다.")
                .doesNotContain("secret_col");
    }
}
