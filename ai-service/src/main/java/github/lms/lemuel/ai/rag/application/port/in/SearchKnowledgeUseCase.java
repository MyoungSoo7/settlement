package github.lms.lemuel.ai.rag.application.port.in;

import github.lms.lemuel.ai.rag.domain.RetrievedChunk;

import java.util.List;

/**
 * 지식베이스 검색 인바운드 포트.
 *
 * <p>챗봇이 내부적으로 쓰는 것과 <b>같은 검색 경로</b>를 API 로도 노출한다 — 답변이 이상할 때
 * "검색이 잘못 찾았나, LLM 이 잘못 읽었나"를 분리해서 볼 수 있어야 운영에서 진단이 가능하다.
 * (RAG 의 대부분의 오답은 LLM 이 아니라 검색 단계에서 발생한다)
 */
public interface SearchKnowledgeUseCase {

    /**
     * @param topK null 이면 설정 기본값(app.ai.rag.top-k)
     * @return 유사도 임계값(app.ai.rag.min-similarity) 을 넘은 결과만, 유사도 내림차순
     */
    List<RetrievedChunk> search(String query, Integer topK);
}
