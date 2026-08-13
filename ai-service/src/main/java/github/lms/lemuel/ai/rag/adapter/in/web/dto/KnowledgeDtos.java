package github.lms.lemuel.ai.rag.adapter.in.web.dto;

import github.lms.lemuel.ai.rag.application.port.in.IngestKnowledgeUseCase.IngestResult;
import github.lms.lemuel.ai.rag.domain.KnowledgeDocument;
import github.lms.lemuel.ai.rag.domain.RetrievedChunk;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** /api/ai/knowledge/** 요청·응답 DTO 모음. */
public final class KnowledgeDtos {

    private KnowledgeDtos() {
    }

    /**
     * 지식 문서 적재 요청.
     *
     * <p>{@code content} 상한(200KB)은 임베딩 비용 상한이다 — 1200자 청크 기준 약 170청크,
     * 즉 요청 1건이 만들 외부 호출 수의 천장을 요청 검증 단계에서 정한다.
     */
    public record IngestRequest(
            @NotBlank(message = "title 은 비어 있을 수 없습니다")
            @Size(max = 200, message = "title 은 200자 이하여야 합니다")
            String title,

            @NotBlank(message = "sourceUri 는 비어 있을 수 없습니다")
            @Size(max = 500, message = "sourceUri 는 500자 이하여야 합니다")
            String sourceUri,

            @NotBlank(message = "content 는 비어 있을 수 없습니다")
            @Size(max = 200_000, message = "content 는 200000자 이하여야 합니다")
            String content
    ) {
    }

    public record IngestResponse(String sourceUri, int chunkCount, boolean skipped, String embeddingModel) {

        public static IngestResponse from(IngestResult result) {
            return new IngestResponse(result.sourceUri(), result.chunkCount(),
                    result.skipped(), result.embeddingModel());
        }
    }

    /**
     * 적재 문서 요약 — 목록 응답의 한 행.
     *
     * <p>문서 {@code id}(UUID)는 싣지 않는다. 외부 계약의 식별자는 {@code sourceUri} 하나이며
     * (삭제 API 도 이것을 받는다), 내부 PK 를 노출하면 쓰이지 않을 두 번째 식별자가 생긴다.
     *
     * <p>{@code contentHash} 는 <b>싣는다</b> — 동기화 도구가 "리포의 문서와 적재분이 같은가"를
     * 재적재(=재임베딩 비용) 없이 판정할 유일한 값이다. 단 이 해시는 <b>PII 마스킹 이후</b>의
     * 본문 해시라, 원본에 마스킹 대상이 있으면 로컬에서 계산한 해시와 다를 수 있다.
     */
    public record DocumentSummaryResponse(String title, String sourceUri, String contentHash,
                                          int chunkCount, java.time.Instant ingestedAt) {

        public static DocumentSummaryResponse from(KnowledgeDocument document) {
            return new DocumentSummaryResponse(document.title(), document.sourceUri(),
                    document.contentHash(), document.chunkCount(), document.ingestedAt());
        }
    }

    public record DocumentsResponse(int count, List<DocumentSummaryResponse> documents) {

        public static DocumentsResponse of(List<KnowledgeDocument> documents) {
            return new DocumentsResponse(documents.size(),
                    documents.stream().map(DocumentSummaryResponse::from).toList());
        }
    }

    public record SearchHitResponse(String title, String sourceUri, String content, double similarity) {

        public static SearchHitResponse from(RetrievedChunk chunk) {
            return new SearchHitResponse(chunk.title(), chunk.sourceUri(), chunk.content(), chunk.similarity());
        }
    }

    public record SearchResponse(String query, List<SearchHitResponse> hits) {

        public static SearchResponse of(String query, List<RetrievedChunk> chunks) {
            return new SearchResponse(query, chunks.stream().map(SearchHitResponse::from).toList());
        }
    }
}
