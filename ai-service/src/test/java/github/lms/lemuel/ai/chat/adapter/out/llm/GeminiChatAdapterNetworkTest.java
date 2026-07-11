package github.lms.lemuel.ai.chat.adapter.out.llm;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GeminiChatAdapter} 의 실 HTTP 왕복(complete·stream) 검증.
 *
 * <p>어댑터가 {@code RestClient.create(baseUrl)} 로 자체 클라이언트를 조립하므로
 * {@code MockRestServiceServer}(빌더 바인딩) 를 쓸 수 없다 — 대신 JDK 내장
 * {@link HttpServer} 를 임시 포트에 띄워 Generative Language API 를 흉내낸다(외부망 없음).
 * generateContent(동기 JSON)·streamGenerateContent(SSE) 두 경로와 HTTP 오류/빈 응답 분기를
 * 전수 실행한다.
 */
class GeminiChatAdapterNetworkTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** baseUrl 을 로컬 서버로 향하게 한 configured 어댑터를 만든다. */
    private GeminiChatAdapter adapterFor(String baseUrl) {
        return new GeminiChatAdapter(new GeminiChatProperties("test-key", "gemini-2.5-flash", baseUrl, 256));
    }

    /** path 판별로 generateContent/streamGenerateContent 응답을 돌려주는 로컬 서버를 띄운다. */
    private String startServer(int status, String syncBody, List<String> sseDataLines) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] payload;
            if (path.contains(":streamGenerateContent")) {
                StringBuilder sse = new StringBuilder();
                for (String line : sseDataLines) {
                    sse.append("data: ").append(line).append("\n\n");
                }
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
    @DisplayName("complete — 실 HTTP 200 generateContent 응답을 파싱한다(이력 포함해 본문 조립)")
    void complete_ok() throws Exception {
        String base = startServer(200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"안녕하세요, 답변입니다.\"}],\"role\":\"model\"}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}",
                List.of());
        GeminiChatAdapter adapter = adapterFor(base);

        List<ChatMessage> history = List.of(
                ChatMessage.user("이전 질문", Instant.now()),
                new ChatMessage(MessageRole.ASSISTANT, "이전 답변", "gemini-2.5-flash", 1, 1, Instant.now()));
        ChatCompletion c = adapter.complete("system", history, "정산 주기?");

        assertThat(c.text()).isEqualTo("안녕하세요, 답변입니다.");
        assertThat(c.model()).isEqualTo("gemini-2.5-flash");
        assertThat(c.inputTokens()).isEqualTo(10);
        assertThat(c.outputTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("complete — HTTP 500 이면 AiUnavailableException 으로 통일(원문 미노출)")
    void complete_httpError_throws() throws Exception {
        String base = startServer(500, "{\"error\":\"boom secret_detail\"}", List.of());
        GeminiChatAdapter adapter = adapterFor(base);

        assertThatThrownBy(() -> adapter.complete("system", List.of(), "질문"))
                .isInstanceOf(AiUnavailableException.class)
                .hasMessageNotContaining("secret_detail");
    }

    @Test
    @DisplayName("stream — SSE 청크를 onDelta 로 흘리고 usageMetadata 토큰을 집계한다")
    void stream_ok() throws Exception {
        String base = startServer(200, null, List.of(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"조각1\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"조각2\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":7}}"));
        GeminiChatAdapter adapter = adapterFor(base);

        List<String> deltas = new CopyOnWriteArrayList<>();
        ChatCompletion c = adapter.stream("system", List.of(), "질문", deltas::add);

        assertThat(deltas).containsExactly("조각1", "조각2");
        assertThat(c.text()).isEqualTo("조각1조각2");
        assertThat(c.inputTokens()).isEqualTo(3);
        assertThat(c.outputTokens()).isEqualTo(7);
    }

    @Test
    @DisplayName("stream — 텍스트 없는 청크만 오면 빈 응답으로 AiUnavailableException")
    void stream_emptyText_throws() throws Exception {
        String base = startServer(200, null, List.of(
                "{\"usageMetadata\":{\"promptTokenCount\":3}}"));
        GeminiChatAdapter adapter = adapterFor(base);

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
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"무시됨\"}]}}]}"));
        GeminiChatAdapter adapter = adapterFor(base);

        assertThatThrownBy(() -> adapter.stream("system", List.of(), "질문", d -> { }))
                .isInstanceOf(AiUnavailableException.class);
    }
}
