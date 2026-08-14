package github.lms.lemuel.ai.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RAG <b>검색·청킹 정책</b> 설정 (prefix=app.ai.rag) — @ConfigurationPropertiesScan 으로 등록.
 *
 * <p>벤더 배선(모델 id·차원·API 키)은 여기가 아니라 어댑터의
 * {@code GeminiEmbeddingProperties}(prefix=app.ai.embedding) 에 있다. 모델명을 두 곳에 두면
 * 한쪽만 바뀌어 "설정은 새 모델인데 저장된 벡터는 옛 모델"이 되므로, 모델 id 의 단일 출처는
 * 어댑터({@code EmbeddingPort.modelId()})다.
 *
 * <p>{@code enabled} 기본값은 <b>false</b> 다. 임베딩은 실비용 외부 호출이고 지식베이스가 비어
 * 있으면 얻는 것이 없으므로, 지식을 넣고 켜는 순서를 강제한다(무행동 착지의 첫 번째 장치).
 *
 * @param enabled           RAG 검색·적재 배선 활성화 (기본 false)
 * @param topK              검색해 프롬프트에 실을 최대 청크 수
 * @param minSimilarity     코사인 유사도 하한 — 못 넘으면 근거로 쓰지 않는다(엉뚱한 자료로 지어내기 방지)
 * @param chunkMaxChars     청크 최대 길이
 * @param chunkOverlapChars 초장문 문단 강제 분할 시 겹칠 길이
 */
@Validated
@ConfigurationProperties(prefix = "app.ai.rag")
public record RagProperties(
        boolean enabled,
        @Min(1) int topK,
        @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity,
        @Min(100) int chunkMaxChars,
        @Min(0) int chunkOverlapChars
) {

    public RagProperties {
        if (topK <= 0) {
            topK = 4;
        }
        if (chunkMaxChars <= 0) {
            chunkMaxChars = 1200;
        }
        if (chunkOverlapChars < 0) {
            chunkOverlapChars = 200;
        }
        if (chunkOverlapChars >= chunkMaxChars) {
            // step = maxChars - overlapChars 가 0 이하면 청킹이 무한 루프다 — 부팅 시점에 거부한다.
            throw new IllegalArgumentException(
                    "app.ai.rag.chunk-overlap-chars 는 chunk-max-chars 보다 작아야 합니다 ("
                            + chunkOverlapChars + " >= " + chunkMaxChars + ")");
        }
    }
}
