package github.lms.lemuel.ai.rag.application.port.out;

import github.lms.lemuel.ai.rag.domain.Embedding;
import github.lms.lemuel.ai.rag.domain.EmbeddedChunk;
import github.lms.lemuel.ai.rag.domain.KnowledgeDocument;
import github.lms.lemuel.ai.rag.domain.RetrievedChunk;

import java.util.List;
import java.util.Optional;

/**
 * 지식베이스 저장·검색 아웃바운드 포트 (pgvector 어댑터가 구현).
 *
 * <p>적재와 검색을 한 포트에 둔다 — 둘은 같은 두 테이블을 같은 불변식(문서 1건 : 청크 N건,
 * 모델별 벡터 공간 분리) 아래에서 다루므로 쪼개면 불변식이 두 곳으로 흩어진다.
 */
public interface KnowledgeBasePort {

    /**
     * 이 모델로 임베딩된 청크가 하나라도 있는가.
     *
     * <p>있으면 검색, 없으면 임베딩 API 호출조차 하지 않는다 —
     * COUNT 가 아니라 EXISTS 인 이유는 "0인지 아닌지"만 필요하고 청크가 늘어도 비용이 일정해야 하기 때문.
     */
    boolean hasChunks(String embeddingModel);

    /** 해당 출처로 이미 적재된 문서의 content_hash (없으면 empty) — 재임베딩 스킵 판정용. */
    Optional<String> findContentHash(String sourceUri);

    /**
     * 문서 + 청크 전량을 원자적으로 교체한다(같은 sourceUri 면 기존 청크 삭제 후 삽입).
     *
     * <p><b>구현은 단일 트랜잭션이어야 한다</b> — 청크 일부만 갱신된 상태는 "옛 답과 새 답이
     * 섞인 근거"라서 어떤 오류보다 나쁘다. 임베딩(외부 API)은 이 호출 <b>밖에서</b> 끝나 있어야 한다.
     */
    void replaceDocument(KnowledgeDocument document, List<EmbeddedChunk> chunks);

    /** 출처 기준 문서 삭제(청크는 FK ON DELETE CASCADE). 삭제된 문서가 있으면 true. */
    boolean deleteBySourceUri(String sourceUri);

    /**
     * 질의 벡터와 가장 가까운 청크 topK 건 (코사인 유사도 내림차순).
     *
     * @param embeddingModel 같은 모델로 임베딩된 청크만 비교한다(벡터 공간 혼용 차단)
     */
    List<RetrievedChunk> searchTopK(Embedding queryEmbedding, String embeddingModel, int topK);
}
