package github.lms.lemuel.ai.rag.application.service;

import github.lms.lemuel.ai.chat.application.port.out.RetrieveContextPort;
import github.lms.lemuel.ai.chat.domain.RetrievedContext;
import github.lms.lemuel.ai.config.RagProperties;
import github.lms.lemuel.ai.rag.application.port.in.SearchKnowledgeUseCase;
import github.lms.lemuel.ai.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.ai.rag.application.port.out.KnowledgeBasePort;
import github.lms.lemuel.ai.rag.domain.Embedding;
import github.lms.lemuel.ai.rag.domain.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 지식베이스 검색 — 두 개의 포트를 <b>한 구현</b>으로 만족시킨다.
 *
 * <ul>
 *   <li>{@link SearchKnowledgeUseCase} — 운영·디버깅용 검색 API 의 인바운드 포트</li>
 *   <li>{@link RetrieveContextPort} — chat 이 선언한 아웃바운드 포트 (여기에 꽂혀 프롬프트 증강이 일어난다)</li>
 * </ul>
 *
 * <p>둘을 같은 클래스로 구현하는 것이 중요하다. API 로 보이는 검색 결과와 챗봇이 실제로 근거로
 * 쓴 검색 결과가 <b>같은 코드에서 나와야</b> "API 로는 잘 찾는데 챗봇은 왜 못 찾나" 같은
 * 재현 불가 상황이 생기지 않는다.
 *
 * <p><b>무행동 착지 3중 장치:</b>
 * <ol>
 *   <li>빈 없음 — {@code app.ai.rag.enabled=false}(기본) 면 이 서비스 자체가 등록되지 않는다.</li>
 *   <li>키 미설정 — 임베딩 API 키가 없으면 빈 리스트.</li>
 *   <li>지식 0건 — 이 모델로 임베딩된 청크가 없으면 <b>임베딩 호출조차 하지 않고</b> 빈 리스트.</li>
 * </ol>
 * 세 경우 모두 프롬프트가 Phase 1 과 바이트 동일해진다.
 */
@Service
@ConditionalOnProperty(name = "app.ai.rag.enabled", havingValue = "true")
public class KnowledgeRetrievalService implements SearchKnowledgeUseCase, RetrieveContextPort {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    private final EmbeddingPort embeddingPort;
    private final KnowledgeBasePort knowledgeBasePort;
    private final RagProperties properties;

    public KnowledgeRetrievalService(EmbeddingPort embeddingPort,
                                    KnowledgeBasePort knowledgeBasePort,
                                    RagProperties properties) {
        this.embeddingPort = embeddingPort;
        this.knowledgeBasePort = knowledgeBasePort;
        this.properties = properties;
    }

    @Override
    public List<RetrievedChunk> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (!embeddingPort.isConfigured()) {
            log.debug("[RAG] 임베딩 키 미설정 — 검색을 건너뛴다");
            return List.of();
        }
        String model = embeddingPort.modelId();
        if (!knowledgeBasePort.hasChunks(model)) {
            // 지식베이스가 비었거나 모델이 바뀌었다 → 유료 임베딩 호출을 아끼고 무근거로 착지.
            log.debug("[RAG] model={} 로 임베딩된 청크 없음 — 검색을 건너뛴다", model);
            return List.of();
        }

        Embedding queryEmbedding = embeddingPort.embedQuery(query);
        int limit = (topK == null || topK <= 0) ? properties.topK() : topK;

        // 임계값 필터는 SQL 이 아니라 여기서 한다 — WHERE 에 거리식을 넣으면 HNSW 인덱스의
        // ORDER BY ... LIMIT 경로를 벗어날 수 있어, 인덱스로 topK 를 먼저 뽑고 나서 걸러낸다.
        List<RetrievedChunk> found = knowledgeBasePort.searchTopK(queryEmbedding, model, limit);
        List<RetrievedChunk> accepted = found.stream()
                .filter(chunk -> chunk.similarity() >= properties.minSimilarity())
                .toList();
        if (accepted.size() < found.size()) {
            log.debug("[RAG] 유사도 하한({}) 미달 {}건 제외", properties.minSimilarity(), found.size() - accepted.size());
        }
        return accepted;
    }

    /** chat 이 부르는 경로 — rag 의 결과를 chat 의 언어({@link RetrievedContext})로 옮겨 준다. */
    @Override
    public List<RetrievedContext> retrieve(String userMessage) {
        return search(userMessage, null).stream()
                .map(chunk -> new RetrievedContext(
                        chunk.title(), chunk.sourceUri(), chunk.content(), chunk.similarity()))
                .toList();
    }
}
