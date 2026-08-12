package github.lms.lemuel.ai.chat.application.port.out;

import github.lms.lemuel.ai.chat.domain.RetrievedContext;

import java.util.List;

/**
 * 사용자 질문에 대한 <b>답변 근거 검색</b> 아웃바운드 포트.
 *
 * <p><b>chat 은 rag 를 모른다(의존성 역전):</b> 이 포트는 chat 이 자기 필요를 자기 언어로 선언한
 * 것이고, 구현은 rag 컨텍스트가 제공해 꽂힌다. 그래서 chat 쪽 코드에 pgvector·임베딩·청크 같은
 * 단어가 한 번도 등장하지 않으며, 검색 구현(벡터 → 키워드 → 하이브리드)을 갈아도 chat 은 그대로다.
 *
 * <p><b>계약:</b> 근거가 없으면 <b>빈 리스트</b>를 반환한다(null 금지, 예외 금지가 원칙).
 * RAG 가 꺼져 있거나 지식베이스가 비어 있을 때도 빈 리스트이며, 그 경우 프롬프트는 종전과
 * 바이트 동일해진다({@link github.lms.lemuel.ai.chat.domain.KnowledgePrompt}).
 */
@FunctionalInterface
public interface RetrieveContextPort {

    /**
     * @param userMessage PII 마스킹이 끝난 사용자 질문
     * @return 유사도 순 근거 목록 (없으면 빈 리스트)
     */
    List<RetrievedContext> retrieve(String userMessage);
}
