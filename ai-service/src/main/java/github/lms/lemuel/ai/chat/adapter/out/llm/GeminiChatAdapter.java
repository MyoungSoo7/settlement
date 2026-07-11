package github.lms.lemuel.ai.chat.adapter.out.llm;

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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Google Gemini(Generative Language API) 기반 {@link ChatCompletionPort} 구현.
 *
 * <p>{@code app.ai.provider=gemini}(기본값) 일 때만 등록된다. company-service 의
 * {@code GeminiSentimentAnalyzer} 와 동일하게 프로젝트 표준 Spring {@link RestClient} 로
 * {@code generateContent}(동기)·{@code streamGenerateContent}(SSE) 를 직접 호출한다
 * (벤더 SDK/Spring AI 미도입 — 키/엔드포인트/포맷 검증 완료).
 *
 * <p>장애 처리(설계 §2.4): 호출/파싱 실패·빈 응답은 {@link AiUnavailableException} 으로 통일해
 * 상위(503)로 올린다. 스트리밍 중 클라이언트 이탈({@link UncheckedIOException})은 그대로 위임한다
 * (정상 이탈을 LLM 실패로 오분류하지 않기 위함 — AnthropicChatAdapter 와 동일 계약).
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiChatAdapter implements ChatCompletionPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatAdapter.class);

    private final GeminiChatProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiChatAdapter(GeminiChatProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create(properties.baseUrl());
        if (!properties.configured()) {
            log.warn("app.ai.gemini.api-key 미설정 — 채팅 API 는 503(AI 미구성)으로 응답합니다. 이력 조회는 정상 동작.");
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
                    .uri("/v1beta/models/{model}:generateContent", properties.model())
                    .header("x-goog-api-key", properties.apiKey())
                    .header("content-type", "application/json")
                    .body(buildBody(systemPrompt, history, userMessage))
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
        try {
            restClient.post()
                    .uri("/v1beta/models/{model}:streamGenerateContent?alt=sse", properties.model())
                    .header("x-goog-api-key", properties.apiKey())
                    .header("content-type", "application/json")
                    .body(buildBody(systemPrompt, history, userMessage))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new AiUnavailableException(
                                    "AI 스트리밍 응답에 실패했습니다. (HTTP " + response.getStatusCode().value() + ")", null);
                        }
                        // SSE: "data: {json}\n" 라인 반복. 각 청크의 텍스트 조각을 즉시 onDelta 로 흘린다.
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) {
                                    continue;
                                }
                                String json = line.substring(5).trim();
                                if (json.isEmpty()) {
                                    continue;
                                }
                                JsonNode chunk = objectMapper.readTree(json);
                                String delta = extractDelta(chunk);
                                if (!delta.isEmpty()) {
                                    text.append(delta);
                                    onDelta.accept(delta);   // 클라이언트 이탈 시 UncheckedIOException → 아래로 위임
                                }
                                JsonNode usage = chunk.path("usageMetadata");
                                if (usage.isObject() && !usage.isEmpty()) {
                                    lastUsage[0] = usage;   // 보통 마지막 청크에 누적 usage 가 실린다
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
        if (text.isEmpty()) {
            throw new AiUnavailableException("AI 가 빈 응답을 반환했습니다.", null);
        }
        return new ChatCompletion(text.toString(), properties.model(),
                tokenOrNull(lastUsage[0], "promptTokenCount"),
                tokenOrNull(lastUsage[0], "candidatesTokenCount"));
    }

    /** Generative Language API 요청 본문 — system_instruction + contents[user/model] + generationConfig. */
    private Map<String, Object> buildBody(String systemPrompt, List<ChatMessage> history, String userMessage) {
        List<Map<String, Object>> contents = new ArrayList<>(history.size() + 1);
        for (ChatMessage past : history) {
            contents.add(Map.of(
                    "role", past.role() == MessageRole.USER ? "user" : "model",
                    "parts", List.of(Map.of("text", past.content()))));
        }
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage))));
        return Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", contents,
                "generationConfig", Map.of("maxOutputTokens", properties.maxTokens()));
    }

    /** 스트리밍 청크(SSE data)에서 텍스트 조각을 뽑는다. 없으면 빈 문자열. */
    static String extractDelta(JsonNode chunk) {
        JsonNode parts = chunk.path("candidates").path(0).path("content").path("parts");
        return (parts.isArray() && !parts.isEmpty()) ? parts.path(0).path("text").asText("") : "";
    }

    private static Integer tokenOrNull(JsonNode usage, String field) {
        return (usage != null && usage.has(field)) ? usage.get(field).asInt() : null;
    }

    /**
     * 비스트리밍 generateContent 응답 파싱 — candidates[0].content.parts[0].text +
     * usageMetadata.{promptTokenCount, candidatesTokenCount}. 빈 candidates/parts/text 는
     * {@link AiUnavailableException} (finishReason·promptFeedback 을 사유로 첨부).
     */
    static ChatCompletion parse(String response, String model, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response == null ? "{}" : response);
        } catch (Exception e) {
            throw new AiUnavailableException("AI 응답 파싱에 실패했습니다.", e);
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            throw new AiUnavailableException(
                    "AI 가 빈 응답을 반환했습니다. (promptFeedback=" + root.path("promptFeedback") + ")", null);
        }
        JsonNode first = candidates.get(0);
        JsonNode parts = first.path("content").path("parts");
        String text = (parts.isArray() && !parts.isEmpty()) ? parts.get(0).path("text").asText("") : "";
        if (text.isBlank()) {
            throw new AiUnavailableException(
                    "AI 가 빈 응답을 반환했습니다. (finishReason=" + first.path("finishReason").asText("") + ")", null);
        }
        JsonNode usage = root.path("usageMetadata");
        return new ChatCompletion(text, model,
                tokenOrNull(usage.isObject() ? usage : null, "promptTokenCount"),
                tokenOrNull(usage.isObject() ? usage : null, "candidatesTokenCount"));
    }
}
