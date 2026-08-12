package github.lms.lemuel.ai.rag.domain;

/**
 * 검색 결과 1건 — 청크 본문 + 출처 + 유사도 (순수 도메인).
 *
 * <p>{@code similarity} 는 코사인 <b>유사도</b>(1 에 가까울수록 유사)다. pgvector 의 {@code <=>} 는
 * 코사인 <b>거리</b>(0 에 가까울수록 유사)를 주므로 어댑터가 {@code 1 - distance} 로 변환해 넣는다 —
 * 도메인·API 에서는 "클수록 좋다"는 한 방향만 쓰기 위한 정규화다(부호 혼동은 흔한 버그원).
 */
public record RetrievedChunk(String title, String sourceUri, String content, double similarity) {

    public RetrievedChunk {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 은 비어 있을 수 없습니다");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 비어 있을 수 없습니다");
        }
    }
}
