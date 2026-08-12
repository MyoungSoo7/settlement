package github.lms.lemuel.ai.rag.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RAG 도메인 값 객체들의 불변식. */
class KnowledgeDomainTest {

    private static final Instant NOW = Instant.parse("2026-08-12T15:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Nested
    @DisplayName("ContentHash")
    class ContentHashTest {

        @Test
        @DisplayName("SHA-256 hex 64자를 돌려주고, 같은 본문은 같은 해시다 (재임베딩 스킵의 근거)")
        void deterministic64HexChars() {
            String hash = ContentHash.of("정산주기는 T+7 영업일입니다.");

            assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
            assertThat(ContentHash.of("정산주기는 T+7 영업일입니다.")).isEqualTo(hash);
        }

        @Test
        @DisplayName("한 글자만 달라도 해시가 달라진다 — 내용 변경을 놓치면 옛 답이 계속 나간다")
        void differsOnAnyChange() {
            assertThat(ContentHash.of("T+7 영업일")).isNotEqualTo(ContentHash.of("T+3 영업일"));
        }

        @Test
        @DisplayName("빈 문자열도 해시된다(0청크 문서와 구분 가능), null 은 거부한다")
        void handlesEmptyRejectsNull() {
            assertThat(ContentHash.of("")).hasSize(64);
            assertThatThrownBy(() -> ContentHash.of(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("KnowledgeDocument")
    class KnowledgeDocumentTest {

        @Test
        @DisplayName("ingest 는 새 id 를 발급한다")
        void ingestAssignsId() {
            KnowledgeDocument document = KnowledgeDocument.ingest("정산 정책", "docs://policy", HASH, 3, NOW);

            assertThat(document.id()).isNotNull();
            assertThat(document.chunkCount()).isEqualTo(3);
            assertThat(document.ingestedAt()).isEqualTo(NOW);
            assertThat(KnowledgeDocument.ingest("정산 정책", "docs://policy", HASH, 3, NOW).id())
                    .isNotEqualTo(document.id());
        }

        @Test
        @DisplayName("title 200자 초과는 잘라낸다 — DB 컬럼 상한과 일치시켜 적재 실패를 막는다")
        void truncatesLongTitle() {
            KnowledgeDocument document = KnowledgeDocument.ingest("제".repeat(250), "docs://x", HASH, 1, NOW);

            assertThat(document.title()).hasSize(200);
        }

        @Test
        @DisplayName("해시 길이가 64가 아니면 거부한다 — 잘린 해시는 중복 적재를 조용히 유발한다")
        void rejectsMalformedHash() {
            assertThatThrownBy(() -> new KnowledgeDocument(UUID.randomUUID(), "t", "u", "abc", 0, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("64");
        }

        @Test
        @DisplayName("필수 값 누락은 거부한다")
        void rejectsMissingFields() {
            assertThatThrownBy(() -> new KnowledgeDocument(null, "t", "u", HASH, 0, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new KnowledgeDocument(UUID.randomUUID(), " ", "u", HASH, 0, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new KnowledgeDocument(UUID.randomUUID(), "t", " ", HASH, 0, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new KnowledgeDocument(UUID.randomUUID(), "t", "u", HASH, -1, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new KnowledgeDocument(UUID.randomUUID(), "t", "u", HASH, 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("EmbeddedChunk / RetrievedChunk")
    class ChunkTest {

        @Test
        @DisplayName("EmbeddedChunk 는 모델 id 를 반드시 들고 다닌다 — 벡터 공간의 출처가 곧 검색 정확성이다")
        void embeddedChunkRequiresModel() {
            EmbeddedChunk chunk = new EmbeddedChunk(0, "본문", Embedding.of(1f, 0f), "gemini-embedding-001");

            assertThat(chunk.embeddingModel()).isEqualTo("gemini-embedding-001");
            assertThatThrownBy(() -> new EmbeddedChunk(0, "본문", Embedding.of(1f), " "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EmbeddedChunk(-1, "본문", Embedding.of(1f), "m"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EmbeddedChunk(0, " ", Embedding.of(1f), "m"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new EmbeddedChunk(0, "본문", null, "m"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("RetrievedChunk 는 제목·본문이 있어야 한다 (근거 표기를 위해 제목이 필수)")
        void retrievedChunkRequiresTitleAndContent() {
            RetrievedChunk chunk = new RetrievedChunk("정산 정책", "docs://policy", "T+3", 0.87);

            assertThat(chunk.similarity()).isEqualTo(0.87);
            assertThatThrownBy(() -> new RetrievedChunk(" ", "u", "c", 0.5))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RetrievedChunk("t", "u", " ", 0.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
