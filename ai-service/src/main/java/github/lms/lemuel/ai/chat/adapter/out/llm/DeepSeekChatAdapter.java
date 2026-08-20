package github.lms.lemuel.ai.chat.adapter.out.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.chat.application.port.out.ChatCompletionPort;
import github.lms.lemuel.ai.chat.domain.ChatCompletion;
import github.lms.lemuel.ai.chat.domain.ChatMessage;
import github.lms.lemuel.ai.chat.domain.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DeepSeek(OpenAI 호환 Chat Completions API) 기반 {@link ChatCompletionPort} 구현.
 *
 * <p>{@code app.ai.provider=deepseek} 일 때만 등록된다. {@link GeminiChatAdapter} 와 같은 이유로
 * 벤더 SDK 없이 프로젝트 표준 {@link RestClient} 로 {@code POST {baseUrl}/chat/completions} 를
 * 직접 호출한다 — 규격이 OpenAI 호환이라 SDK 를 물 이유가 없고, 그 덕에 <b>baseUrl 만 바꾸면
 * 로컬 Ollama(OpenAI 호환 엔드포인트)도 같은 코드로 부른다</b>(외부 크레딧 없이 개발·데모 가능).
 *
 * <p>사고과정(chain-of-thought) 차단 — reasoning 계열 모델은 두 방식으로 사고를 흘린다:
 * <ul>
 *   <li>{@code deepseek-reasoner}: 별도 필드 {@code reasoning_content} → 아예 읽지 않는다.</li>
 *   <li>Ollama {@code deepseek-r1}: {@code content} 안에 인라인 {@code <think>…</think>} →
 *       {@link ThinkTagFilter} 가 청크 경계를 넘어가며 걷어낸다.</li>
 * </ul>
 * 돈을 다루는 플랫폼의 챗봇이라 시스템 프롬프트·내부 추론이 사용자에게 노출되면 안 된다.
 *
 * <p>장애 처리(설계 §2.4): 호출/파싱 실패·빈 응답은 {@link AiUnavailableException} 으로 통일해
 * 상위(503)로 올린다. 스트리밍 중 클라이언트 이탈({@link UncheckedIOException})은 그대로 위임한다
 * (정상 이탈을 LLM 실패로 오분류하지 않기 위함 — 다른 두 어댑터와 동일 계약).
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "deepseek")
public class DeepSeekChatAdapter implements ChatCompletionPort {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekChatAdapter.class);

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String SSE_DONE = "[DONE]";

    private final DeepSeekChatProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeepSeekChatAdapter(DeepSeekChatProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create(properties.baseUrl());
        if (!properties.configured()) {
            log.warn("app.ai.deepseek.api-key 미설정 — 채팅 API 는 503(AI 미구성)으로 응답합니다. 이력 조회는 정상 동작.");
        }
    }

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public ChatCompletion complete(String systemPrompt, List<ChatMessage> history, String userMessage) {
        String response;
        try {
            response = restClient.post()
                    .uri(COMPLETIONS_PATH)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("content-type", "application/json")
                    .body(buildBody(systemPrompt, history, userMessage, false))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new AiUnavailableException("AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        return parse(response, properties.model(), objectMapper);
    }

    @Override
    public ChatCompletion stream(String systemPrompt, List<ChatMessage> history, String userMessage,
                                 Consumer<String> onDelta) {
        StringBuilder text = new StringBuilder();
        JsonNode[] lastUsage = new JsonNode[1];
        ThinkTagFilter think = new ThinkTagFilter();
        try {
            restClient.post()
                    .uri(COMPLETIONS_PATH)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("content-type", "application/json")
                    .body(buildBody(systemPrompt, history, userMessage, true))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new AiUnavailableException(
                                    "AI 스트리밍 응답에 실패했습니다. (HTTP " + response.getStatusCode().value() + ")", null);
                        }
                        // SSE: "data: {json}\n" 라인 반복, 마지막은 "data: [DONE]".
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String json = line.substring(5).trim();
                                if (json.isEmpty() || SSE_DONE.equals(json)) {
                                    continue;
                                }
                                JsonNode chunk = readChunk(json);
                                String delta = think.apply(extractDelta(chunk));
                                if (!delta.isEmpty()) {
                                    text.append(delta);
                                    onDelta.accept(delta);   // 클라이언트 이탈 시 UncheckedIOException → 아래로 위임
                                }
                                JsonNode usage = chunk.path("usage");
                                if (usage.isObject() && !usage.isEmpty()) {
                                    lastUsage[0] = usage;   // include_usage 옵션 → [DONE] 직전 청크에 누적 usage
                                }
                            }
                        }
                        return null;
                    });
        } catch (AiUnavailableException | UncheckedIOException e) {
            // 이미 분류된 LLM 실패, 그리고 클라이언트 이탈(onDelta 유래)은 그대로 위임한다.
            throw e;
        } catch (RuntimeException e) {
            throw new AiUnavailableException("AI 스트리밍 응답에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        // 태그 조각으로 스트림이 끝났으면 보류분을 되돌린다(본문 유실 금지).
        String tail = think.flush();
        if (!tail.isEmpty()) {
            text.append(tail);
            onDelta.accept(tail);
        }
        if (text.isEmpty()) {
            throw new AiUnavailableException("AI 가 빈 응답을 반환했습니다.", null);
        }
        return new ChatCompletion(text.toString(), properties.model(),
                tokenOrNull(lastUsage[0], "prompt_tokens"),
                tokenOrNull(lastUsage[0], "completion_tokens"));
    }

    private JsonNode readChunk(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AiUnavailableException("AI 스트리밍 응답 파싱에 실패했습니다.", e);
        }
    }

    /** OpenAI 호환 요청 본문 — messages[system, …history, user] + max_tokens + stream. */
    private Map<String, Object> buildBody(String systemPrompt, List<ChatMessage> history, String userMessage,
                                          boolean stream) {
        List<Map<String, Object>> messages = new ArrayList<>(history.size() + 2);
        messages.add(Map.of("role", "system", "content", systemPrompt));
        for (ChatMessage past : history) {
            messages.add(Map.of(
                    "role", past.role() == MessageRole.USER ? "user" : "assistant",
                    "content", past.content()));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", messages);
        body.put("max_tokens", properties.maxTokens());
        body.put("stream", stream);
        if (stream) {
            // 이 옵션이 없으면 스트리밍 응답에 usage 가 실리지 않아 비용 집계 근거가 사라진다.
            body.put("stream_options", Map.of("include_usage", true));
        }
        return body;
    }

    /**
     * 스트리밍 청크에서 사용자에게 보일 텍스트 조각을 뽑는다. 없으면 빈 문자열.
     *
     * <p>{@code delta.reasoning_content}(사고과정)는 의도적으로 읽지 않는다. 또 종료 청크는
     * {@code "content": null} 로 오므로 반드시 텍스트 노드인지 확인한다 — {@code asText("")} 는
     * NullNode 에 대해 문자열 {@code "null"} 을 돌려주기 때문이다(그대로 채팅에 새어나간다).
     */
    static String extractDelta(JsonNode chunk) {
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode content = choices.get(0).path("delta").path("content");
        return content.isTextual() ? content.asText() : "";
    }

    /**
     * 비스트리밍 chat/completions 응답 파싱 — choices[0].message.content +
     * usage.{prompt_tokens, completion_tokens}. 빈 choices/content 는 {@link AiUnavailableException}
     * (error·finish_reason 을 사유로 첨부).
     */
    static ChatCompletion parse(String response, String model, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response == null ? "{}" : response);
        } catch (Exception e) {
            throw new AiUnavailableException("AI 응답 파싱에 실패했습니다.", e);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new AiUnavailableException(
                    "AI 가 빈 응답을 반환했습니다. (error=" + root.path("error") + ")", null);
        }
        JsonNode first = choices.get(0);
        JsonNode content = first.path("message").path("content");
        String text = ThinkTagFilter.stripAll(content.isTextual() ? content.asText() : "");
        if (text.isBlank()) {
            throw new AiUnavailableException(
                    "AI 가 빈 응답을 반환했습니다. (finish_reason=" + first.path("finish_reason").asText("") + ")", null);
        }
        JsonNode usage = root.path("usage");
        JsonNode usageOrNull = usage.isObject() ? usage : null;
        return new ChatCompletion(text, model,
                tokenOrNull(usageOrNull, "prompt_tokens"),
                tokenOrNull(usageOrNull, "completion_tokens"));
    }

    private static Integer tokenOrNull(JsonNode usage, String field) {
        return (usage != null && usage.path(field).isNumber()) ? usage.get(field).asInt() : null;
    }

    /**
     * {@code content} 안에 인라인으로 섞여 오는 {@code <think>…</think>} 구간을 걷어내는 필터.
     *
     * <p>스트리밍에서는 태그가 청크 경계에 걸쳐 도착한다({@code "<thi"} + {@code "nk>"}). 그래서
     * 상태를 들고, 태그의 <b>접두사가 될 수 있는 꼬리</b>만 보류했다가 다음 청크와 이어 붙여 판정한다.
     * 보류분은 {@link #flush()} 로 회수해 본문 유실을 막고, 닫히지 않은 사고 블록은 흘리지 않는다.
     */
    static final class ThinkTagFilter {

        private static final String OPEN = "<think>";
        private static final String CLOSE = "</think>";

        private final StringBuilder pending = new StringBuilder();
        private boolean inside;
        private boolean trimLeading;

        String apply(String delta) {
            if (delta == null || delta.isEmpty()) {
                return "";
            }
            pending.append(delta);
            StringBuilder out = new StringBuilder();
            while (true) {
                if (inside) {
                    int close = pending.indexOf(CLOSE);
                    if (close < 0) {
                        dropAllButTailPrefixOf(CLOSE);
                        break;
                    }
                    pending.delete(0, close + CLOSE.length());
                    inside = false;
                    trimLeading = true;   // 사고 블록 뒤에 붙는 개행은 흔적이므로 함께 지운다
                } else {
                    int open = pending.indexOf(OPEN);
                    if (open < 0) {
                        int keep = tailPrefixLength(OPEN);
                        out.append(pending, 0, pending.length() - keep);
                        pending.delete(0, pending.length() - keep);
                        break;
                    }
                    out.append(pending, 0, open);
                    pending.delete(0, open + OPEN.length());
                    inside = true;
                }
            }
            return emit(out.toString());
        }

        /** 스트림 종료 시 보류분 회수. 닫히지 않은 사고 블록은 버린다(노출 금지). */
        String flush() {
            if (inside) {
                pending.setLength(0);
                return "";
            }
            String rest = pending.toString();
            pending.setLength(0);
            return emit(rest);
        }

        /** 완결된 텍스트에서 한 번에 걷어낸다(비스트리밍 경로). */
        static String stripAll(String text) {
            if (text == null || text.isEmpty()) {
                return "";
            }
            ThinkTagFilter filter = new ThinkTagFilter();
            return filter.apply(text) + filter.flush();
        }

        private String emit(String s) {
            if (!trimLeading || s.isEmpty()) {
                return s;
            }
            String stripped = s.stripLeading();
            if (!stripped.isEmpty()) {
                trimLeading = false;
            }
            return stripped;
        }

        private void dropAllButTailPrefixOf(String tag) {
            pending.delete(0, pending.length() - tailPrefixLength(tag));
        }

        /** pending 의 꼬리 중 {@code tag} 의 접두사가 될 수 있는 가장 긴 길이(없으면 0). */
        private int tailPrefixLength(String tag) {
            int max = Math.min(tag.length() - 1, pending.length());
            for (int k = max; k > 0; k--) {
                if (tailMatchesPrefix(tag, k)) {
                    return k;
                }
            }
            return 0;
        }

        private boolean tailMatchesPrefix(String tag, int k) {
            int offset = pending.length() - k;
            for (int i = 0; i < k; i++) {
                if (pending.charAt(offset + i) != tag.charAt(i)) {
                    return false;
                }
            }
            return true;
        }
    }
}
