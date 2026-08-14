package github.lms.lemuel.ai.rag.application.service;

import github.lms.lemuel.ai.chat.domain.RetrievedContext;
import github.lms.lemuel.ai.config.RagProperties;
import github.lms.lemuel.ai.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.ai.rag.application.port.out.KnowledgeBasePort;
import github.lms.lemuel.ai.rag.domain.Embedding;
import github.lms.lemuel.ai.rag.domain.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalServiceTest {

    private static final String MODEL = "gemini-embedding-001";
    private static final Embedding QUERY_VECTOR = Embedding.of(1f, 0f);

    @Mock EmbeddingPort embeddingPort;
    @Mock KnowledgeBasePort knowledgeBasePort;

    private KnowledgeRetrievalService service;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties(true, 4, 0.55, 1200, 200);
        service = new KnowledgeRetrievalService(embeddingPort, knowledgeBasePort, properties);
    }

    @Test
    @DisplayName("검색 — 설정된 top-k 로 조회하고 유사도 하한 미달은 버린다")
    void search_filtersBelowMinSimilarity() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.hasChunks(MODEL)).thenReturn(true);
        when(embeddingPort.embedQuery("정산주기")).thenReturn(QUERY_VECTOR);
        when(knowledgeBasePort.searchTopK(QUERY_VECTOR, MODEL, 4)).thenReturn(List.of(
                new RetrievedChunk("정산 정책", "docs://a", "T+3 영업일", 0.91),
                new RetrievedChunk("무관한 문서", "docs://b", "회식 공지", 0.31)));

        List<RetrievedChunk> hits = service.search("정산주기", null);

        // 무관한 근거를 붙이는 것은 근거 없이 답하는 것보다 나쁘다 — 하한 미달은 제외한다.
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).title()).isEqualTo("정산 정책");
    }

    @Test
    @DisplayName("topK 를 명시하면 그 값이 쓰이고, 0 이하·null 이면 설정값으로 되돌아간다")
    void search_topKOverride() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.hasChunks(MODEL)).thenReturn(true);
        when(embeddingPort.embedQuery(anyString())).thenReturn(QUERY_VECTOR);
        when(knowledgeBasePort.searchTopK(any(), anyString(), anyInt())).thenReturn(List.of());

        service.search("질문", 7);
        service.search("질문", 0);

        verify(knowledgeBasePort).searchTopK(QUERY_VECTOR, MODEL, 7);
        verify(knowledgeBasePort).searchTopK(QUERY_VECTOR, MODEL, 4);
    }

    @Test
    @DisplayName("지식 0건 — 유료 임베딩 호출조차 하지 않고 빈 결과로 착지한다")
    void search_noChunks_skipsEmbeddingCall() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.hasChunks(MODEL)).thenReturn(false);

        assertThat(service.search("질문", null)).isEmpty();

        verify(embeddingPort, never()).embedQuery(anyString());
        verify(knowledgeBasePort, never()).searchTopK(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("모델이 바뀌면 그 모델의 청크가 없으므로 검색이 조용히 비활성화된다 (뒤섞인 벡터 공간 차단)")
    void search_afterModelChange_returnsEmpty() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn("gemini-embedding-2");
        when(knowledgeBasePort.hasChunks("gemini-embedding-2")).thenReturn(false);

        assertThat(service.search("질문", null)).isEmpty();

        verify(knowledgeBasePort).hasChunks("gemini-embedding-2");
    }

    @Test
    @DisplayName("임베딩 키 미설정 — DB 도 건드리지 않고 빈 결과")
    void search_notConfigured() {
        when(embeddingPort.isConfigured()).thenReturn(false);

        assertThat(service.search("질문", null)).isEmpty();

        verifyNoInteractions(knowledgeBasePort);
    }

    @Test
    @DisplayName("빈 질의 — 임베딩·DB 어느 쪽도 호출하지 않는다")
    void search_blankQuery() {
        assertThat(service.search(null, null)).isEmpty();
        assertThat(service.search("  ", null)).isEmpty();

        verifyNoInteractions(embeddingPort, knowledgeBasePort);
    }

    @Test
    @DisplayName("retrieve — 같은 검색 결과를 chat 의 언어(RetrievedContext)로 옮긴다")
    void retrieve_mapsToChatLanguage() {
        when(embeddingPort.isConfigured()).thenReturn(true);
        when(embeddingPort.modelId()).thenReturn(MODEL);
        when(knowledgeBasePort.hasChunks(MODEL)).thenReturn(true);
        when(embeddingPort.embedQuery("정산주기")).thenReturn(QUERY_VECTOR);
        when(knowledgeBasePort.searchTopK(eq(QUERY_VECTOR), eq(MODEL), anyInt())).thenReturn(List.of(
                new RetrievedChunk("정산 정책", "docs://a", "T+3 영업일", 0.91)));

        List<RetrievedContext> contexts = service.retrieve("정산주기");

        assertThat(contexts).singleElement().satisfies(context -> {
            assertThat(context.title()).isEqualTo("정산 정책");
            assertThat(context.sourceUri()).isEqualTo("docs://a");
            assertThat(context.content()).isEqualTo("T+3 영업일");
            assertThat(context.similarity()).isEqualTo(0.91);
        });
    }

    @Test
    @DisplayName("retrieve 도 근거가 없으면 빈 리스트 — chat 은 원본 프롬프트로 착지한다")
    void retrieve_emptyWhenNothingFound() {
        when(embeddingPort.isConfigured()).thenReturn(false);

        assertThat(service.retrieve("질문")).isEmpty();
    }
}
