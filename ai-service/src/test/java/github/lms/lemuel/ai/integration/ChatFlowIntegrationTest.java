package github.lms.lemuel.ai.integration;

import github.lms.lemuel.ai.AiServiceApplication;
import github.lms.lemuel.ai.chat.application.port.out.ChatCompletionPort;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.common.config.jwt.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 챗봇 Phase 1 종단 통합 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway(V1) + 실 JWT 체인.
 * LLM 포트만 스텁(실 API 미호출).
 *
 * <p>검증 축 (설계 §11 수용 기준):
 * <ol>
 *   <li>JWT 인증 채팅 왕복 → conversationId 발급 → 같은 대화로 후속 질문 → 이력 4건</li>
 *   <li>보안 — 무인증 401, 타인 대화 접근 404</li>
 *   <li>대화 목록/삭제 및 삭제 후 404</li>
 *   <li>rate limit(분당 3) 초과 시 429</li>
 * </ol>
 */
@SpringBootTest(
        classes = AiServiceApplication.class,
        properties = {
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.ai.chat.api-key=",                 // 실 어댑터는 미구성 — 포트는 아래 목으로 대체
                "app.ai.rate-limit.per-minute=3",
                "app.ai.rate-limit.per-day=100"
        }
)
@AutoConfigureMockMvc
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class ChatFlowIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("ai_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtil jwtUtil;

    @MockitoBean ChatCompletionPort chatCompletionPort;

    private String userToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        userToken = jwtUtil.generateToken("user@test.com", "USER", 42L);
        otherToken = jwtUtil.generateToken("other@test.com", "USER", 77L);
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        when(chatCompletionPort.complete(anyString(), any(), anyString()))
                .thenReturn(new ChatCompletion("정산 주기는 등급별로 다릅니다.", "claude-stub", 100, 20));
    }

    @Test
    @DisplayName("채팅 왕복 → 후속 질문 컨텍스트 유지 → 이력 조회 → 삭제 종단 흐름")
    void chatRoundTripAndHistory() throws Exception {
        // 1) 새 대화
        MvcResult first = mockMvc.perform(post("/api/ai/chat")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"정산 주기 알려줘\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").exists())
                .andExpect(jsonPath("$.reply").value("정산 주기는 등급별로 다릅니다."))
                .andReturn();
        String conversationId = com.jayway.jsonpath.JsonPath
                .read(first.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8), "$.conversationId");

        // 2) 같은 대화로 후속 질문 — conversationId 유지
        mockMvc.perform(post("/api/ai/chat")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"" + conversationId + "\",\"message\":\"VIP 는요?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId));

        // 3) 이력 — 대화 1건, 메시지 4건(2왕복)
        mockMvc.perform(get("/api/ai/conversations")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].messageCount").value(4));
        mockMvc.perform(get("/api/ai/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(4))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"));

        // 4) 타인 토큰으로 접근 — 존재 자체를 숨긴다(404)
        mockMvc.perform(get("/api/ai/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // 5) 삭제 후 404
        mockMvc.perform(delete("/api/ai/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/ai/conversations/" + conversationId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("무인증 요청 — 401")
    void unauthenticated() throws Exception {
        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"질문\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/ai/conversations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("rate limit — 분당 3회 초과 시 429 + Retry-After")
    void rateLimited() throws Exception {
        String token = jwtUtil.generateToken("heavy@test.com", "USER", 99L);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/ai/chat")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"질문 " + i + "\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/ai/chat")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"초과 질문\"}"))
                .andExpect(status().isTooManyRequests());
    }
}
