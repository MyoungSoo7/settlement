package github.lms.lemuel.ai.rag.application.service;

import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import github.lms.lemuel.ai.config.RagProperties;
import github.lms.lemuel.ai.rag.application.port.in.IngestKnowledgeUseCase.IngestCommand;
import github.lms.lemuel.ai.rag.application.port.in.IngestKnowledgeUseCase.IngestResult;
import github.lms.lemuel.ai.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.ai.rag.application.port.out.KnowledgeBasePort;
import github.lms.lemuel.ai.rag.domain.ContentHash;
import github.lms.lemuel.ai.rag.domain.EmbeddedChunk;
import github.lms.lemuel.ai.rag.domain.Embedding;
import github.lms.lemuel.ai.rag.domain.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestKnowledgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T15:00:00Z");
    private static final String MODEL = "gemini-embedding-001";

    @Mock EmbeddingPort embeddingPort;
    @Mock KnowledgeBasePort knowledgeBasePort;

    private IngestKnowledgeService service;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties(true, 4, 0.55, 1200, 200);
        service = new IngestKnowledgeService(embeddingPort, knowledgeBasePort, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("신규 문서 — 청크마다 임베딩이 붙고 문서+청크가 한 번에 교체된다")
    void ingest_newDocument() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.findContentHash("docs://policy")).thenReturn(Optional.empty());
        when(embeddingPort.embedDocuments(anyList()))
                .thenReturn(List.of(Embedding.of(1f, 0f), Embedding.of(0f, 1f)));

        IngestResult result = service.ingest(new IngestCommand(
                "정산 정책", "docs://policy", "가".repeat(1000) + "\n\n" + "나".repeat(1000)));

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.skipped()).isFalse();
        assertThat(result.embeddingModel()).isEqualTo(MODEL);

        ArgumentCaptor<KnowledgeDocument> document = ArgumentCaptor.forClass(KnowledgeDocument.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddedChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(knowledgeBasePort).replaceDocument(document.capture(), chunks.capture());
        assertThat(document.getValue().sourceUri()).isEqualTo("docs://policy");
        assertThat(document.getValue().ingestedAt()).isEqualTo(NOW);
        assertThat(document.getValue().chunkCount()).isEqualTo(2);
        // 청크 index 는 0부터 연속이어야 한다 — UNIQUE(document_id, chunk_index) 와 대응된다.
        assertThat(chunks.getValue()).extracting(EmbeddedChunk::index).containsExactly(0, 1);
        assertThat(chunks.getValue()).allSatisfy(chunk ->
                assertThat(chunk.embeddingModel()).isEqualTo(MODEL));
    }

    @Test
    @DisplayName("본문 해시가 같으면 재임베딩하지 않고 스킵한다 (재적재 비용 0)")
    void ingest_sameContent_isSkipped() {
        String content = "변하지 않은 본문입니다.";
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.findContentHash("docs://policy"))
                .thenReturn(Optional.of(ContentHash.of(content)));

        IngestResult result = service.ingest(new IngestCommand("정산 정책", "docs://policy", content));

        assertThat(result.skipped()).isTrue();
        assertThat(result.chunkCount()).isZero();
        verify(embeddingPort, never()).embedDocuments(anyList());
        verify(knowledgeBasePort, never()).replaceDocument(any(), anyList());
    }

    @Test
    @DisplayName("본문이 바뀌면 스킵하지 않고 다시 적재한다")
    void ingest_changedContent_isReingested() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.findContentHash("docs://policy"))
                .thenReturn(Optional.of(ContentHash.of("옛 본문")));
        when(embeddingPort.embedDocuments(anyList())).thenReturn(List.of(Embedding.of(1f)));

        IngestResult result = service.ingest(new IngestCommand("정산 정책", "docs://policy", "새 본문"));

        assertThat(result.skipped()).isFalse();
        verify(knowledgeBasePort).replaceDocument(any(), anyList());
    }

    @Test
    @DisplayName("적재 본문도 PII 마스킹을 거친다 — 청크는 프롬프트로 나가는 새 유출 경로다")
    void ingest_masksPii() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.findContentHash(anyString())).thenReturn(Optional.empty());
        when(embeddingPort.embedDocuments(anyList())).thenReturn(List.of(Embedding.of(1f)));

        service.ingest(new IngestCommand("고객 안내", "docs://guide",
                "결제 카드 4111-1111-1111-1111 관련 안내입니다."));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> embedded = ArgumentCaptor.forClass(List.class);
        verify(embeddingPort).embedDocuments(embedded.capture());
        assertThat(embedded.getValue()).allSatisfy(text -> assertThat(text).doesNotContain("4111"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmbeddedChunk>> chunks = ArgumentCaptor.forClass(List.class);
        verify(knowledgeBasePort).replaceDocument(any(), chunks.capture());
        assertThat(chunks.getValue()).allSatisfy(chunk ->
                assertThat(chunk.content()).doesNotContain("4111"));
    }

    @Test
    @DisplayName("임베딩 개수가 청크 수와 다르면 저장 전에 막는다 — 어긋난 벡터는 조용한 오답이다")
    void ingest_embeddingCountMismatch_failsBeforeSave() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(knowledgeBasePort.findContentHash(anyString())).thenReturn(Optional.empty());
        when(embeddingPort.embedDocuments(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> service.ingest(new IngestCommand("제목", "docs://x", "본문")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("임베딩 개수");

        verify(knowledgeBasePort, never()).replaceDocument(any(), anyList());
    }

    @Test
    @DisplayName("임베딩 키 미설정 — 503 로 안내하고 DB 는 건드리지 않는다")
    void ingest_notConfigured() {
        when(embeddingPort.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.ingest(new IngestCommand("제목", "docs://x", "본문")))
                .isInstanceOf(AiNotConfiguredException.class);

        verifyNoInteractions(knowledgeBasePort);
    }

    @Test
    @DisplayName("빈 문서는 커맨드 경계에서 이미 거부된다 — 서비스의 0청크 가드는 그 뒤의 2차 방어다")
    void blankContent_isRejectedAtTheBoundary() {
        // 공백만인 본문은 IngestCommand 생성 시점에 막히므로 서비스까지 도달하지 못한다.
        // (0청크 문서를 저장하면 검색에 절대 잡히지 않는 유령 행이 남는다 — 두 겹으로 막는다)
        assertThatThrownBy(() -> new IngestCommand("제목", "docs://x", "\n\n \t \n\n"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(embeddingPort, knowledgeBasePort);
    }

    @Test
    @DisplayName("삭제는 포트 결과를 그대로 전달한다 (없는 문서 = false → 404)")
    void deleteBySourceUri() {
        when(knowledgeBasePort.deleteBySourceUri("docs://gone")).thenReturn(false);
        when(knowledgeBasePort.deleteBySourceUri("docs://here")).thenReturn(true);

        assertThat(service.deleteBySourceUri("docs://gone")).isFalse();
        assertThat(service.deleteBySourceUri("docs://here")).isTrue();
    }

    @Test
    @DisplayName("커맨드 검증 — 제목·출처·본문 누락은 생성 시점에 거부된다")
    void command_validation() {
        assertThatThrownBy(() -> new IngestCommand(" ", "docs://x", "본문"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestCommand("제목", " ", "본문"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IngestCommand("제목", "docs://x", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
