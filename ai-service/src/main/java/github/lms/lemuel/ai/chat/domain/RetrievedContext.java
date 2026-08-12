package github.lms.lemuel.ai.chat.domain;

/**
 * 챗봇이 답변 근거로 실을 참고 자료 1건 (순수 도메인).
 *
 * <p>chat 컨텍스트가 <b>자기 언어로</b> 정의한 타입이다 — rag 의 {@code RetrievedChunk} 를 그대로
 * 쓰지 않는다. chat 은 "제목 있는 근거 텍스트"만 알면 되고, 그것이 벡터 검색에서 왔는지
 * 키워드 검색·외부 API 에서 왔는지 알 필요가 없다. 이 경계가 있어야 검색 방식 교체가
 * chat 의 계약 변경 없이 가능하다.
 */
public record RetrievedContext(String title, String sourceUri, String content, double similarity) {

    public RetrievedContext {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 은 비어 있을 수 없습니다");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 비어 있을 수 없습니다");
        }
    }
}
