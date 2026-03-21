package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.domain.DocumentChunk;
import github.lms.lemuel.rag.domain.EntityType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class VectorSearchJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<DocumentChunk> findSimilar(float[] queryEmbedding, int maxResults, double threshold) {
        String vectorStr = Arrays.toString(queryEmbedding);
        String sql = """
            SELECT id, entity_type, entity_id, content,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM opslab.document_embedding
            WHERE 1 - (embedding <=> ?::vector) >= ?
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> mapToDocumentChunk(rs),
                vectorStr, vectorStr, threshold, vectorStr, maxResults);
    }

    public void insertWithEmbedding(EntityType entityType, Long entityId, String content, float[] embedding) {
        String vectorStr = Arrays.toString(embedding);
        String sql = """
            INSERT INTO opslab.document_embedding (entity_type, entity_id, content, embedding, created_at, updated_at)
            VALUES (?, ?, ?, ?::vector, now(), now())
            ON CONFLICT (entity_type, entity_id)
            DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding, updated_at = now()
            """;

        jdbcTemplate.update(sql, entityType.name(), entityId, content, vectorStr);
    }

    public long count() {
        Long result = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM opslab.document_embedding", Long.class);
        return result != null ? result : 0;
    }

    private DocumentChunk mapToDocumentChunk(ResultSet rs) throws SQLException {
        return DocumentChunk.builder()
                .id(rs.getLong("id"))
                .entityType(EntityType.valueOf(rs.getString("entity_type")))
                .entityId(rs.getLong("entity_id"))
                .content(rs.getString("content"))
                .similarity(rs.getDouble("similarity"))
                .build();
    }
}
