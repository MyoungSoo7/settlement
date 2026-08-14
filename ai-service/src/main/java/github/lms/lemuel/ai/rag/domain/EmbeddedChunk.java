package github.lms.lemuel.ai.rag.domain;

/**
 * 저장 직전의 청크 — 텍스트 + 그 텍스트의 임베딩 + 만든 모델 (순수 도메인).
 *
 * <p>{@code embeddingModel} 을 벡터와 같은 레코드에 묶어 두는 것이 핵심이다. 모델이 바뀌면
 * 벡터 공간이 바뀌므로 서로 다른 모델의 벡터를 비교하면 <b>에러 없이 무의미한 순위</b>가 나온다.
 * 모델 id 를 청크에 동행시켜 검색 시 같은 모델끼리만 비교하도록 강제한다.
 */
public record EmbeddedChunk(int index, String content, Embedding embedding, String embeddingModel) {

    public EmbeddedChunk {
        if (index < 0) {
            throw new IllegalArgumentException("index 는 0 이상이어야 합니다");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 비어 있을 수 없습니다");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding 은 필수입니다");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("embeddingModel 은 필수입니다");
        }
    }
}
