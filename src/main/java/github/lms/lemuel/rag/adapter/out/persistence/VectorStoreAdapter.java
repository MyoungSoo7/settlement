package github.lms.lemuel.rag.adapter.out.persistence;

import github.lms.lemuel.rag.application.port.out.VectorSearchPort;
import github.lms.lemuel.rag.domain.DocumentChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class VectorStoreAdapter implements VectorSearchPort {

    private final VectorSearchJdbcRepository vectorSearchJdbcRepository;

    @Override
    public List<DocumentChunk> searchSimilar(float[] queryEmbedding, int maxResults, double threshold) {
        return vectorSearchJdbcRepository.findSimilar(queryEmbedding, maxResults, threshold);
    }

    @Override
    public void save(DocumentChunk chunk) {
        vectorSearchJdbcRepository.insertWithEmbedding(
                chunk.getEntityType(), chunk.getEntityId(), chunk.getContent(), chunk.getEmbedding());
    }

    @Override
    public void saveAll(List<DocumentChunk> chunks) {
        chunks.forEach(this::save);
    }

    @Override
    public long count() {
        return vectorSearchJdbcRepository.count();
    }
}
