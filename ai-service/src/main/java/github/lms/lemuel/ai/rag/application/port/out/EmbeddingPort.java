package github.lms.lemuel.ai.rag.application.port.out;

import github.lms.lemuel.ai.rag.domain.Embedding;

import java.util.List;

/**
 * 텍스트 → 임베딩 벡터 아웃바운드 포트.
 *
 * <p><b>문서용·질의용을 분리한 이유:</b> Gemini 의 {@code gemini-embedding-001} 은 요청에
 * {@code task_type} 을 받아 같은 텍스트라도 "색인될 문서"(RETRIEVAL_DOCUMENT)와
 * "검색 질의"(RETRIEVAL_QUERY)를 다른 벡터로 만든다(비대칭 검색). 이 구분을 어댑터 내부의
 * 플래그로 숨기면 호출자가 잘못된 task_type 을 쓰기 쉬우므로 <b>포트 시그니처로 드러낸다</b>.
 *
 * <p>둘을 뒤바꿔 쓰면 예외 없이 검색 품질만 조용히 나빠진다 — 그래서 계약으로 못박는다.
 */
public interface EmbeddingPort {

    /** API 키가 설정돼 있는가. false 면 RAG 는 아무 근거도 반환하지 않는다(챗봇은 정상 동작). */
    boolean isConfigured();

    /**
     * 이 어댑터가 사용하는 임베딩 모델 id.
     * 청크에 함께 저장돼 "같은 모델끼리만 비교"를 강제하는 키가 된다.
     */
    String modelId();

    /** 색인 대상 문서 조각 임베딩 (task_type=RETRIEVAL_DOCUMENT). */
    Embedding embedDocument(String text);

    /** 여러 청크 임베딩 — 입력 순서와 출력 순서가 1:1 대응한다. */
    List<Embedding> embedDocuments(List<String> texts);

    /** 검색 질의 임베딩 (task_type=RETRIEVAL_QUERY). */
    Embedding embedQuery(String text);
}
