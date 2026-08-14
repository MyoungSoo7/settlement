package github.lms.lemuel.ai.rag.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 지식 문서 — 임베딩 원본 1건 (순수 도메인).
 *
 * <p>{@code sourceUri} 가 자연키다. 같은 출처를 다시 적재하면 새 문서가 아니라 <b>교체</b>이며,
 * {@code contentHash} 가 같으면 교체조차 하지 않는다(재임베딩 스킵).
 */
public record KnowledgeDocument(
        UUID id,
        String title,
        String sourceUri,
        String contentHash,
        int chunkCount,
        Instant ingestedAt
) {

    public KnowledgeDocument {
        if (id == null) {
            throw new IllegalArgumentException("id 는 필수입니다");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title 은 비어 있을 수 없습니다");
        }
        if (sourceUri == null || sourceUri.isBlank()) {
            throw new IllegalArgumentException("sourceUri 는 비어 있을 수 없습니다");
        }
        if (contentHash == null || contentHash.length() != 64) {
            throw new IllegalArgumentException("contentHash 는 SHA-256 hex 64자여야 합니다");
        }
        if (chunkCount < 0) {
            throw new IllegalArgumentException("chunkCount 는 0 이상이어야 합니다");
        }
        if (ingestedAt == null) {
            throw new IllegalArgumentException("ingestedAt 은 필수입니다");
        }
        // 제목은 프롬프트에 인용되고 DB 컬럼이 VARCHAR(200) 이므로 도메인에서 먼저 자른다.
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
    }

    /** 신규 적재 — id 는 서버가 만든다(클라이언트 추측 불가, chat_conversations 와 동일 정책). */
    public static KnowledgeDocument ingest(String title, String sourceUri, String contentHash,
                                          int chunkCount, Instant now) {
        return new KnowledgeDocument(UUID.randomUUID(), title, sourceUri, contentHash, chunkCount, now);
    }
}
