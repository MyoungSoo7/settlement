package github.lms.lemuel.ai.rag.adapter.out.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.ai.chat.application.exception.AiUnavailableException;
import github.lms.lemuel.ai.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.ai.rag.domain.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini(Generative Language API) 기반 {@link EmbeddingPort} 구현.
 *
 * <p>{@code GeminiChatAdapter} 와 동일한 방식 — Spring {@link RestClient} 로
 * {@code POST /v1beta/models/{model}:embedContent} 를 직접 호출한다.
 *
 * <p><b>왜 Spring AI 를 쓰지 않는가:</b> ArchUnit 이 {@code org.springframework.ai..} 사용을
 * {@code ..ai.chat.adapter.out.llm..} 안으로만 허용한다(설계 §9). 새 패키지에서 쓰면 빌드가 깨지고,
 * 그 룰을 넓히는 것은 "벤더 타입 격리"라는 원래 의도를 훼손한다. 이미 검증된 RestClient 패턴이
 * 있으므로 그것을 재사용하는 편이 룰을 손대는 것보다 싸다.
 *
 * <p><b>task_type 을 반드시 넣는다:</b> {@code gemini-embedding-001} 은 색인 대상 문서
 * (RETRIEVAL_DOCUMENT)와 검색 질의(RETRIEVAL_QUERY)를 다른 벡터로 만든다(비대칭 검색).
 * 이를 생략하면 예외 없이 검색 품질만 떨어져 원인을 찾기 어렵다.
 *
 * <p><b>L2 정규화를 반드시 한다:</b> Google 문서는 {@code gemini-embedding-001} 의 출력을 기본
 * 3072 차원이 아닌 값으로 잘라 쓸 때 정규화를 직접 하라고 명시한다. 여기서는 768 로 잘라 쓰므로
 * 대상이다 — 정규화는 {@link Embedding#l2Normalized()} 가 담당한다.
 *
 * <p>배치는 아직 쓰지 않는다: 청크마다 {@code embedContent} 를 순차 호출한다. 적재는 관리자
 * 오프라인 작업이라 지연이 문제되지 않고, 검증하지 않은 배치 엔드포인트 스펙을 추측해 넣는 것보다
 * 낫다(후속 과제 — ADR 0034 「구현 체크리스트」).
 */
@Component
@ConditionalOnProperty(name = "app.ai.rag.enabled", havingValue = "true")
public class GeminiEmbeddingAdapter implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingAdapter.class);

    private static final String TASK_DOCUMENT = "RETRIEVAL_DOCUMENT";
    private static final String TASK_QUERY = "RETRIEVAL_QUERY";

    private final GeminiEmbeddingProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GeminiEmbeddingAdapter(GeminiEmbeddingProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create(properties.baseUrl());
        if (!properties.configured()) {
            log.warn("app.ai.embedding.api-key 미설정 — RAG 는 근거 0건으로 동작합니다(챗봇은 정상). "
                    + "지식 적재 API 는 503(AI 미구성)으로 응답합니다.");
        }
    }

    @Override
    public boolean isConfigured() {
        return properties.configured();
    }

    @Override
    public String modelId() {
        return properties.model();
    }

    @Override
    public Embedding embedDocument(String text) {
        return embed(text, TASK_DOCUMENT);
    }

    @Override
    public List<Embedding> embedDocuments(List<String> texts) {
        List<Embedding> embeddings = new ArrayList<>(texts.size());
        for (String text : texts) {
            embeddings.add(embed(text, TASK_DOCUMENT));
        }
        return embeddings;
    }

    @Override
    public Embedding embedQuery(String text) {
        return embed(text, TASK_QUERY);
    }

    private Embedding embed(String text, String taskType) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("임베딩할 텍스트가 비어 있습니다");
        }
        String response;
        try {
            response = restClient.post()
                    .uri("/v1beta/models/{model}:embedContent", properties.model())
                    .header("x-goog-api-key", properties.apiKey())
                    .header("content-type", "application/json")
                    .body(buildBody(text, taskType))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new AiUnavailableException("임베딩 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.", e);
        }
        // 잘라낸 벡터는 단위벡터가 아니므로 정규화한 뒤 저장·검색에 쓴다(코사인 순위 오염 방지).
        return parse(response, objectMapper).l2Normalized();
    }

    /** embedContent 요청 본문 — content.parts[].text + task_type + output_dimensionality. */
    private Map<String, Object> buildBody(String text, String taskType) {
        return Map.of(
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "task_type", taskType,
                "output_dimensionality", properties.dimension());
    }

    /**
     * embedContent 응답 파싱 — {@code embedding.values[]}.
     * 빈 응답·비배열은 {@link AiUnavailableException} 으로 통일해 상위(503)로 올린다
     * ({@code GeminiChatAdapter} 와 동일 계약).
     */
    static Embedding parse(String response, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response == null ? "{}" : response);
        } catch (Exception e) {
            throw new AiUnavailableException("임베딩 응답 파싱에 실패했습니다.", e);
        }
        JsonNode values = root.path("embedding").path("values");
        if (!values.isArray() || values.isEmpty()) {
            throw new AiUnavailableException("임베딩 API 가 빈 응답을 반환했습니다.", null);
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            JsonNode element = values.get(i);
            if (!element.isNumber()) {
                throw new AiUnavailableException("임베딩 응답에 숫자가 아닌 값이 있습니다: index=" + i, null);
            }
            vector[i] = (float) element.asDouble();
        }
        return Embedding.of(vector);
    }
}
