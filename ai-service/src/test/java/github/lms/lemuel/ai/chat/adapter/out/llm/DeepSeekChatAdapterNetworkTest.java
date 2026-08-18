package github.lms.lemuel.ai.chat.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DeepSeekChatAdapter} 의 실 HTTP 왕복(complete·stream) 검증.
 *
 * <p>{@link GeminiChatAdapterNetworkTest} 와 같은 이유로 JDK 내장 {@link HttpServer} 를 임시 포트에
 * 띄워 OpenAI 호환 {@code /chat/completions} 를 흉내낸다(외부망 없음). 어댑터가
 * {@code RestClient.create(baseUrl)} 로 자체 클라이언트를 조립하므로 MockRestServiceServer 는 쓸 수 없다.
 *
 * <p>★ 이 테스트가 로컬 Ollama 호환성의 근거이기도 하다 — 서버는 그저 "OpenAI 호환 엔드포인트"일 뿐이고,
 * 어댑터는 baseUrl 만 바꿔 붙는다.
 */
class DeepSeekChatAdapterNetworkTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DeepSeekChatAdapter adapterFor(String baseUrl) {
        return new DeepSeekChatAdapter(new DeepSeekChatProperties("test-key", "deepseek-chat", baseUrl, 256));
    }

    /** stream=true 여부로 SSE/JSON 응답을 갈라 돌려주는 로컬 서버를 띄운다. */
    private String startServer(int status, String syncBody, List<String> sseLines) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(body);
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            byte[] payload;
            if (body.contains("\"stream\":true")) {
                StringBuilder sse = new StringBuilder();
                for (String line : sseLines) {
                    sse.append("data: ").append(line).append("\n\n");
                }
                sse.append("data: [DONE]\n\n");
                payload = sse.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            } else {
                payload = (syncBody == null ? "" : syncBody).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("complete — 실 HTTP 200 chat/completions 응답을 파싱한다")
    void complete_ok() throws Exception {
        String base = startServer(200, """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"안녕하세요, 답변입니다."},
                "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}
                """, List.of());
        DeepSeekChatAdapter adapter = adapterFor(base);

        List<ChatMessage> history = List.of(
                ChatMessage.user("이전 질문", Instant.now()),
                new ChatMessage(MessageRole.ASSISTANT, "이전 답변", "deepseek-chat", 1, 1, Instant.now()));
        ChatCompletion c = adapter.complete("system", history, "정산 주기?");

        assertThat(c.text()).isEqualTo("안녕하세요, 답변입니다.");
        assertThat(c.model()).isEqualTo("deepseek-chat");
        assertThat(c.inputTokens()).isEqualTo(10);
        assertThat(c.outputTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("complete — 요청 규격: Bearer 인증 + /chat/completions + system·history·user 순 messages")
    void complete_requestShape() throws Exception {
        String base = startServer(200, """
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                """, List.of());
        DeepSeekChatAdapter adapter = adapterFor(base);

        adapter.complete("너는 도우미다", List.of(
                ChatMessage.user("이전 질문", Instant.now()),
                new ChatMessage(MessageRole.ASSISTANT, "이전 답변", "deepseek-chat", 1, 1, Instant.now())),
                "정산 주기?");

        assertThat(lastAuth.get()).isEqualTo("Bearer test-key");
        assertThat(lastPath.get()).isEqualTo("/chat/completions");

        JsonNode body = OM.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.path("stream").asBoolean()).isFalse();

        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("너는 도우미다");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(3).path("content").asText()).isEqualTo("정산 주기?");
    }

    @Test
    @DisplayName("complete — HTTP 500 이면 AiUnavailableException 으로 통일(원문 미노출)")
    void complete_httpError_throws() throws Exception {
        String base = startServer(500, "{\"error\":{\"message\":\"boom secret_detail\"}}", List.of());
        DeepSeekChatAdapter adapter = adapterFor(base);

        assertThatThrownBy(() -> adapter.complete("system", List.of(), "질문"))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageNotContaining("secret_detail");
    }

    @Test
    @DisplayName("stream — SSE delta 를 onDelta 로 흘리고 [DONE] 앞 usage 청크로 토큰을 집계한다")
    void stream_ok() throws Exception {
        String base = startServer(200, null, List.of(
                "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"조각1\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"조각2\"},\"finish_reason\":\"stop\"}]}",
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":7}}"));
        DeepSeekChatAdapter adapter = adapterFor(base);

        List<String> deltas = new CopyOnWriteArrayList<>();
        ChatCompletion c = adapter.stream("system", List.of(), "질문", deltas::add);

        assertThat(deltas).containsExactly("조각1", "조각2");
        assertThat(c.text()).isEqualTo("조각1조각2");
        assertThat(c.inputTokens()).isEqualTo(3);
        assertThat(c.outputTokens()).isEqualTo(7);
    }

    @Test
    @DisplayName("stream — 요청에 stream=true + usage 포함 옵션이 실린다")
    void stream_requestShape() throws Exception {
        String base = startServer(200, null, List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));
        adapterFor(base).stream("system", List.of(), "질문", d -> { });

        JsonNode body = OM.readTree(lastBody.get());
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("stream — Ollama deepseek-r1 의 인라인 <think> 는 청크 경계를 넘어가도 새지 않는다")
    void stream_thinkTagStripped() throws Exception {
        String base = startServer(200, null, List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"<thi\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"nk>내부 사고</thi\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"nk>T+7 영업일입니다.\"}}]}"));
        DeepSeekChatAdapter adapter = adapterFor(base);

        List<String> deltas = new CopyOnWriteArrayList<>();
        ChatCompletion c = adapter.stream("system", List.of(), "질문", deltas::add);

        assertThat(c.text()).isEqualTo("T+7 영업일입니다.");
        assertThat(String.join("", deltas)).isEqualTo("T+7 영업일입니다.");
        assertThat(String.join("", deltas)).doesNotContain("내부 사고");
    }

    @Test
    @DisplayName("stream — 텍스트 없는 청크만 오면 빈 응답으로 AiUnavailableException")
    void stream_emptyText_throws() throws Exception {
        String base = startServer(200, null, List.of(
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":3}}"));
        DeepSeekChatAdapter adapter = adapterFor(base);

        List<String> deltas = new ArrayList<>();
        assertThatThrownBy(() -> adapter.stream("system", List.of(), "질문", deltas::add))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageContaining("빈 응답");
        assertThat(deltas).isEmpty();
    }

    @Test
    @DisplayName("stream — HTTP 오류 상태면 즉시 AiUnavailableException")
    void stream_httpError_throws() throws Exception {
        String base = startServer(503, null, List.of(
                "{\"choices\":[{\"delta\":{\"content\":\"무시됨\"}}]}"));
        DeepSeekChatAdapter adapter = adapterFor(base);

        assertThatThrownBy(() -> adapter.stream("system", List.of(), "질문", d -> { }))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("stream — 깨진 SSE 청크는 AiUnavailableException 으로 통일")
    void stream_malformedChunk_throws() throws Exception {
        String base = startServer(200, null, List.of("not-json{"));
        DeepSeekChatAdapter adapter = adapterFor(base);

        assertThatThrownBy(() -> adapter.stream("system", List.of(), "질문", d -> { }))
                .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("키 미설정 — 부팅은 되고 isConfigured()=false (채팅만 503)")
    void notConfigured_bootsButReportsUnconfigured() {
        DeepSeekChatAdapter adapter = new DeepSeekChatAdapter(
                new DeepSeekChatProperties("", null, null, 0));
        assertThat(adapter.isConfigured()).isFalse();
    }
}
