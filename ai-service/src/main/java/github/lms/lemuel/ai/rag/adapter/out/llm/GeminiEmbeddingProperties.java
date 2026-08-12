package github.lms.lemuel.ai.rag.adapter.out.llm;

import github.lms.lemuel.ai.rag.domain.Embedding;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gemini 임베딩 연동 설정 (prefix=app.ai.embedding) — {@code GeminiChatProperties} 와 같은 형태.
 *
 * <p>채팅과 <b>같은 API 키·같은 base URL</b>을 쓴다(같은 Generative Language API). 설정에서 기본값이
 * {@code GEMINI_API_KEY} 를 가리키게 이어 두므로 키를 두 벌 관리하지 않는다.
 *
 * <p>모델·차원이 여기(어댑터)에 있고 {@code app.ai.rag.*}(검색·청킹 정책)와 분리돼 있는 것이 의도다 —
 * 벤더를 갈면 이 레코드만 교체되고 검색 정책은 손대지 않는다.
 *
 * @param apiKey    x-goog-api-key — 미설정이면 RAG 는 근거 0건(챗봇은 정상 동작)
 * @param model     기본 gemini-embedding-001. 청크에 함께 저장돼 모델별 분리 비교의 키가 된다
 * @param baseUrl   기본 https://generativelanguage.googleapis.com
 * @param dimension output_dimensionality — <b>마이그레이션의 vector(N) 과 일치해야 한다</b>
 */
@ConfigurationProperties(prefix = "app.ai.embedding")
public record GeminiEmbeddingProperties(String apiKey, String model, String baseUrl, int dimension) {

    public GeminiEmbeddingProperties {
        if (model == null || model.isBlank()) {
            model = "gemini-embedding-001";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://generativelanguage.googleapis.com";
        }
        if (dimension <= 0) {
            dimension = 768;
        }
        if (apiKey == null) {
            apiKey = "";
        }
        // pgvector 의 vector 타입은 2000 차원까지만 인덱스 가능하다(공식 README).
        // 넘겨도 테이블은 만들어지지만 매 질의가 전량 스캔이 되므로 부팅 시점에 막는다.
        if (dimension > Embedding.MAX_INDEXABLE_DIMENSION) {
            throw new IllegalArgumentException(
                    "app.ai.embedding.dimension 은 " + Embedding.MAX_INDEXABLE_DIMENSION
                            + " 이하여야 합니다 (pgvector vector 타입의 인덱스 상한). 요청: " + dimension
                            + " — 더 큰 차원이 필요하면 halfvec 로 전환하는 새 마이그레이션이 필요하다");
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }
}
