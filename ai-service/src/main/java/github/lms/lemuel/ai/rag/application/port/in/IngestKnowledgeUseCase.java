package github.lms.lemuel.ai.rag.application.port.in;

/**
 * 지식 문서 적재 인바운드 포트 (관리자 전용).
 *
 * <p>같은 {@code sourceUri} 로 다시 적재하면 새 문서가 아니라 <b>교체</b>다. 본문이 이전과
 * 동일하면 임베딩(외부 유료 호출)까지 건너뛰고 {@code skipped=true} 로 알린다 — 멱등에 가까운
 * 재실행 안전성을 API 계약으로 노출한다.
 */
public interface IngestKnowledgeUseCase {

    IngestResult ingest(IngestCommand command);

    /** 출처 기준 삭제. 삭제된 문서가 있으면 true. */
    boolean deleteBySourceUri(String sourceUri);

    record IngestCommand(String title, String sourceUri, String content) {
        public IngestCommand {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title 은 비어 있을 수 없습니다");
            }
            if (sourceUri == null || sourceUri.isBlank()) {
                throw new IllegalArgumentException("sourceUri 는 비어 있을 수 없습니다");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content 는 비어 있을 수 없습니다");
            }
        }
    }

    /**
     * @param skipped 본문 해시가 이전과 같아 재임베딩을 건너뛴 경우 true
     */
    record IngestResult(String sourceUri, int chunkCount, boolean skipped, String embeddingModel) {
    }
}
