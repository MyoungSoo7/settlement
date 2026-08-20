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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * SSE 스트림이 <b>HTTP 프로토콜상 정상 종료</b>되는지 검증한다 — 실 Tomcat 커넥터 + 실 HTTP 클라이언트.
 *
 * <p>★ MockMvc 로는 이 결함을 볼 수 없다. 문제는 응답 <b>본문의 내용</b>이 아니라 chunked 전송이
 * 종결자 없이 끊기는 것이고, 그건 실제 커넥터를 통과해야만 드러난다. 그래서 이 테스트만
 * {@code webEnvironment=RANDOM_PORT} 를 쓴다.
 *
 * <p><b>회귀 대상(2026-08-19)</b>: {@code emitter.complete()} 는 Tomcat 의 ASYNC 디스패치를
 * 유발하고, 이때 Spring Security 필터 체인이 다시 돈다. 그런데 {@code JwtAuthenticationFilter} 는
 * {@code OncePerRequestFilter} 라 <b>ASYNC 디스패치에서 기본적으로 건너뛰어진다</b>
 * ({@code shouldNotFilterAsyncDispatch()} 기본값 true). 인증이 비어 있으니
 * {@code anyRequest().hasAnyRole(...)} 가 AccessDenied 를 던지고, 응답은 이미 커밋된 뒤라
 * 에러 페이지도 못 쓰면서 커넥션이 종결자 없이 끊긴다.
 *
 * <p>증상: {@code curl} 은 exit 18(CURLE_PARTIAL_FILE), 브라우저 {@code fetch} 리더는 완료
 * 신호를 못 받아 채팅 UI 가 "응답 중…"에 영구히 갇힌다(대화는 서버에 저장돼 있는데도).
 */
@SpringBootTest(
        classes = AiServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.ai.chat.api-key="          // 실 어댑터 미구성 — 포트는 아래 목으로 대체
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class ChatStreamTerminationIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    // ChatFlowIntegrationTest 와 같은 이유로 pgvector 이미지여야 한다(마이그레이션이 확장을 만든다).
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @MockitoBean
    ChatCompletionPort chatCompletionPort;

    private String userToken;

    @BeforeEach
    void setUp() {
        userToken = jwtUtil.generateToken("stream-user@test.com", "USER", 4242L);
        when(chatCompletionPort.isConfigured()).thenReturn(true);
        when(chatCompletionPort.stream(anyString(), any(), anyString(), any())).thenAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(3);
            onDelta.accept("정산 주기는 ");
            onDelta.accept("등급별로 다릅니다.");
            return new ChatCompletion("정산 주기는 등급별로 다릅니다.", "stub-model", 10, 5);
        });
    }

    @Test
    @DisplayName("SSE 스트림이 프로토콜상 정상 종료된다 — 본문을 끝까지 읽어도 전송이 끊기지 않는다")
    void sseStreamTerminatesCleanly() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/ai/chat/stream"))
                .header("Authorization", "Bearer " + userToken)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"정산 주기 알려줘\"}"))
                .build();

        AtomicReference<HttpResponse<String>> captured = new AtomicReference<>();
        // 본문을 끝까지 읽는다. 서버가 chunked 종결자 없이 끊으면 여기서 IOException 이 난다
        // (curl 의 exit 18 과 같은 사건 — 클라이언트는 "완료"를 관측하지 못한다).
        assertThatCode(() -> captured.set(client.send(request, HttpResponse.BodyHandlers.ofString())))
                .as("SSE 응답이 종결자 없이 끊기면 안 된다 (ASYNC 디스패치 인가 실패 회귀)")
                .doesNotThrowAnyException();

        HttpResponse<String> response = captured.get();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("delta 와 done 이 모두 도착해야 한다")
                .contains("event:delta")
                .contains("event:done")
                .contains("정산 주기는 등급별로 다릅니다.");
    }
}
