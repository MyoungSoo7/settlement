package github.lms.lemuel.ai.chat.adapter.in.web;

import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import github.lms.lemuel.ai.chat.application.exception.ConversationNotFoundException;
import github.lms.lemuel.ai.chat.application.exception.RateLimitExceededException;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatCommand;
import github.lms.lemuel.ai.chat.application.port.in.ChatUseCase.ChatResult;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
                        new ConversationController(conversationQuery))
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
}
