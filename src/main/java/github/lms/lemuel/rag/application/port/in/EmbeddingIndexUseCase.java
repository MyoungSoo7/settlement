package github.lms.lemuel.rag.application.port.in;

public interface EmbeddingIndexUseCase {
    record IndexResult(int indexed, int skipped, int failed) {}
    IndexResult indexAll();
}
