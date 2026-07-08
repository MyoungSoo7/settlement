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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Google Gemini(Generative Language API) 기반 {@link ChatCompletionPort} 구현.
 *
 * <p>{@code app.ai.provider=gemini}(기본값) 일 때만 등록된다. company-service 의
 * {@code GeminiSentimentAnalyzer} 와 동일하게 프로젝트 표준 Spring {@link RestClient} 로
 * {@code generateContent} 를 직접 호출한다(벤더 SDK/Spring AI 미도입 — 키/엔드포인트/포맷 검증 완료).
 *
 * <p>장애 처리(설계 §2.4): 호출/파싱 실패·빈 응답은 {@link AiUnavailableException} 으로 통일해
 * 상위(503)로 올린다. 키 미설정 시 {@link #isConfigured()} 가 false → 채팅만 503, 이력 조회는 정상.
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
        String response = call(systemPrompt, history, userMessage);
        return parse(response, properties.model(), objectMapper);
    }

    @Override
    public ChatCompletion stream(String systemPrompt, List<ChatMessage> history, String userMessage,
                                 Consumer<String> onDelta) {
        // Gemini 어댑터는 비스트리밍 generateContent 를 호출하고 전체 텍스트를 한 번에 delta 로 흘린다.
        // (토큰 단위 스트리밍 미구현 — SSE 클라이언트 관점에선 done 과 동일 결과. 필요 시 streamGenerateContent 로 확장.)
        ChatCompletion completion = complete(systemPrompt, history, userMessage);
        onDelta.accept(completion.text());
        return completion;
    }

    private String call(String systemPrompt, List<ChatMessage> history, String userMessage) {
        List<Map<String, Object>> contents = new ArrayList<>(history.size() + 1);
        for (ChatMessage past : history) {
            contents.add(Map.of(
                    "role", past.role() == MessageRole.USER ? "user" : "model",
                    "parts", List.of(Map.of("text", past.content()))));
        }
        contents.add(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage))));

        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                "contents", contents,
                "generationConfig", Map.of("maxOutputTokens", properties.maxTokens()));

        try {
            return restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", properties.model())
                    .header("x-goog-api-key", properties.apiKey())
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            // 원문(키·상세)이 상위로 새지 않도록 안전 메시지로 감싸고 원인만 cause 로 보존.
            throw new AiUnavailableException("AI 응답 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
    }

    /**
     * Generative Language API 응답에서 텍스트/토큰을 추출한다.
     * candidates[0].content.parts[0].text · usageMetadata.{promptTokenCount, candidatesTokenCount}.
     * 빈 candidates/parts/text 는 {@link AiUnavailableException} (finishReason·promptFeedback 을 사유로 첨부).
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
        Integer inputTokens = usage.has("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null;
        Integer outputTokens = usage.has("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null;
        return new ChatCompletion(text, model, inputTokens, outputTokens);
    }
}
