package github.lms.lemuel.rag.application.port.out;

import github.lms.lemuel.rag.domain.DocumentChunk;

import java.util.List;

public interface VectorSearchPort {
    List<DocumentChunk> searchSimilar(float[] queryEmbedding, int maxResults, double threshold);
    void save(DocumentChunk chunk);
    void saveAll(List<DocumentChunk> chunks);
    long count();
}
