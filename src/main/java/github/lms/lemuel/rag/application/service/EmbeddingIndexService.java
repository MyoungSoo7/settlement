package github.lms.lemuel.rag.application.service;

import github.lms.lemuel.rag.application.port.in.EmbeddingIndexUseCase;
import github.lms.lemuel.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.rag.application.port.out.VectorSearchPort;
import github.lms.lemuel.rag.domain.DocumentChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingIndexService implements EmbeddingIndexUseCase {

    private final DocumentChunker documentChunker;
    private final EmbeddingPort embeddingPort;
    private final VectorSearchPort vectorSearchPort;

    @Override
    public IndexResult indexAll() {
        List<DocumentChunker.ChunkData> chunks = documentChunker.chunkAll();
        log.info("RAG 인덱싱 시작: 총 {}건", chunks.size());

        AtomicInteger indexed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        int batchSize = 20;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<DocumentChunker.ChunkData> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            try {
                List<String> texts = batch.stream().map(DocumentChunker.ChunkData::content).toList();
                List<float[]> embeddings = embeddingPort.embedBatch(texts);

                for (int j = 0; j < batch.size(); j++) {
                    DocumentChunker.ChunkData chunk = batch.get(j);
                    DocumentChunk docChunk = DocumentChunk.builder()
                            .entityType(chunk.entityType())
                            .entityId(chunk.entityId())
                            .content(chunk.content())
                            .embedding(embeddings.get(j))
                            .build();
                    vectorSearchPort.save(docChunk);
                    indexed.incrementAndGet();
                }
            } catch (Exception e) {
                log.error("배치 임베딩 실패 (index {}~{}): {}", i, i + batchSize, e.getMessage());
                failed.addAndGet(batch.size());
            }
        }

        log.info("RAG 인덱싱 완료: indexed={}, failed={}", indexed.get(), failed.get());
        return new IndexResult(indexed.get(), 0, failed.get());
    }
}
