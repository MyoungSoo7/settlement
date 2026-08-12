package github.lms.lemuel.ai.rag.adapter.out.persistence;

import github.lms.lemuel.ai.rag.application.port.out.KnowledgeBasePort;
import github.lms.lemuel.ai.rag.domain.EmbeddedChunk;
import github.lms.lemuel.ai.rag.domain.Embedding;
import github.lms.lemuel.ai.rag.domain.KnowledgeDocument;
import github.lms.lemuel.ai.rag.domain.RetrievedChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * pgvector 기반 {@link KnowledgeBasePort} 구현 — <b>JdbcTemplate</b> (JPA 아님).
 *
 * <p><b>왜 @Entity 가 아닌가:</b> ai-service 는 {@code spring.jpa.hibernate.ddl-auto: validate} 로
 * 뜬다. Hibernate 가 모르는 {@code vector} 타입 컬럼을 매핑하면 스키마 검증 단계에서 부팅이
 * 실패하고, 통과시키려면 커스텀 {@code UserType} 등록 + 방언 확장이 필요하다. 얻는 것(엔티티 편의)
 * 보다 잃는 것(부팅 실패 위험, 프레임워크 결합)이 크다. 벡터 검색은 어차피 {@code <=>} 연산자와
 * HNSW 인덱스를 직접 다뤄야 하는 네이티브 영역이라 JdbcTemplate 이 정직한 선택이다.
 * (선례: {@code config/PartitionMaintenanceRunner} 도 네이티브 SQL 을 JdbcTemplate 으로 호출한다)
 *
 * <p><b>벡터 바인딩:</b> 커스텀 JDBC 타입을 등록하지 않고 {@code ?::vector} 캐스팅으로 넘긴다.
 * 직렬화는 도메인({@link Embedding#toPgVectorLiteral()})이 책임진다.
 *
 * <p>이 클래스는 JaCoCo 커버리지 제외 경로({@code **}{@code /adapter/out/persistence/**}) 에 있고,
 * 실제 검증은 {@code RagKnowledgeIntegrationTest} 가 <b>실 PostgreSQL + 실 pgvector</b> 로 한다 —
 * SQL 과 인덱스는 목으로 검증할 수 없기 때문이다.
 */
@Component
@ConditionalOnProperty(name = "app.ai.rag.enabled", havingValue = "true")
public class KnowledgeBaseJdbcAdapter implements KnowledgeBasePort {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeBaseJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean hasChunks(String embeddingModel) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM knowledge_chunks WHERE embedding_model = ?)",
                Boolean.class, embeddingModel);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<String> findContentHash(String sourceUri) {
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT content_hash FROM knowledge_documents WHERE source_uri = ?",
                String.class, sourceUri);
        return hashes.stream().findFirst();
    }

    /**
     * 문서 + 청크 전량 교체.
     *
     * <p>단일 트랜잭션이어야 하는 이유: 청크가 일부만 갱신된 상태는 옛 답과 새 답이 섞인 근거라
     * 어떤 예외보다 나쁘다. 삭제 후 삽입 사이에서 실패하면 전부 롤백돼 이전 상태가 유지된다.
     */
    @Override
    @Transactional
    public void replaceDocument(KnowledgeDocument document, List<EmbeddedChunk> chunks) {
        // 같은 출처가 있으면 UPDATE(id 는 유지) — 청크는 아래에서 전량 교체한다.
        int updated = jdbcTemplate.update("""
                UPDATE knowledge_documents
                   SET title = ?, content_hash = ?, chunk_count = ?, ingested_at = ?
                 WHERE source_uri = ?
                """, document.title(), document.contentHash(), chunks.size(),
                java.sql.Timestamp.from(document.ingestedAt()), document.sourceUri());

        java.util.UUID documentId;
        if (updated == 0) {
            documentId = document.id();
            jdbcTemplate.update("""
                    INSERT INTO knowledge_documents (id, title, source_uri, content_hash, chunk_count, ingested_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, documentId, document.title(), document.sourceUri(), document.contentHash(),
                    chunks.size(), java.sql.Timestamp.from(document.ingestedAt()));
        } else {
            documentId = jdbcTemplate.queryForObject(
                    "SELECT id FROM knowledge_documents WHERE source_uri = ?",
                    java.util.UUID.class, document.sourceUri());
            jdbcTemplate.update("DELETE FROM knowledge_chunks WHERE document_id = ?", documentId);
        }

        for (EmbeddedChunk chunk : chunks) {
            jdbcTemplate.update("""
                    INSERT INTO knowledge_chunks (document_id, chunk_index, content, embedding, embedding_model)
                    VALUES (?, ?, ?, ?::vector, ?)
                    """, documentId, chunk.index(), chunk.content(),
                    chunk.embedding().toPgVectorLiteral(), chunk.embeddingModel());
        }
    }

    @Override
    @Transactional
    public boolean deleteBySourceUri(String sourceUri) {
        // 청크는 FK ON DELETE CASCADE 로 함께 지워진다.
        return jdbcTemplate.update("DELETE FROM knowledge_documents WHERE source_uri = ?", sourceUri) > 0;
    }

    /**
     * 코사인 거리 최근접 topK.
     *
     * <p>{@code ORDER BY embedding <=> ? LIMIT k} 형태여야 HNSW 인덱스를 탄다 — 거리식을 WHERE 로
     * 옮기면 인덱스 스캔 경로를 벗어날 수 있으므로 유사도 임계값 필터는 애플리케이션에서 한다.
     *
     * <p>{@code <=>} 는 코사인 <b>거리</b>(0=동일)이므로 {@code 1 - distance} 로 유사도로 바꿔 올린다.
     *
     * <p>{@code embedding_model} 필터는 서로 다른 벡터 공간의 혼용을 막는다 — 없으면 모델 교체 후
     * 예외 없이 무의미한 순위가 나온다(HNSW 에서는 후필터링이므로 단일 모델 운영이 정상 상태다).
     */
    @Override
    public List<RetrievedChunk> searchTopK(Embedding queryEmbedding, String embeddingModel, int topK) {
        String literal = queryEmbedding.toPgVectorLiteral();
        return jdbcTemplate.query("""
                SELECT d.title, d.source_uri, c.content, 1 - (c.embedding <=> ?::vector) AS similarity
                  FROM knowledge_chunks c
                  JOIN knowledge_documents d ON d.id = c.document_id
                 WHERE c.embedding_model = ?
                 ORDER BY c.embedding <=> ?::vector
                 LIMIT ?
                """,
                (rs, rowNum) -> new RetrievedChunk(
                        rs.getString("title"),
                        rs.getString("source_uri"),
                        rs.getString("content"),
                        rs.getDouble("similarity")),
                literal, embeddingModel, literal, topK);
    }
}
