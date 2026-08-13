package github.lms.lemuel.ai.rag.application.service;

import github.lms.lemuel.ai.chat.domain.PiiMasker;
import github.lms.lemuel.common.log.LogSafe;
import github.lms.lemuel.ai.config.RagProperties;
import github.lms.lemuel.ai.rag.application.port.in.IngestKnowledgeUseCase;
import github.lms.lemuel.ai.rag.application.port.in.ListKnowledgeDocumentsUseCase;
import github.lms.lemuel.ai.rag.application.port.out.EmbeddingPort;
import github.lms.lemuel.ai.rag.application.port.out.KnowledgeBasePort;
import github.lms.lemuel.ai.rag.domain.ContentHash;
import github.lms.lemuel.ai.rag.domain.EmbeddedChunk;
import github.lms.lemuel.ai.rag.domain.Embedding;
import github.lms.lemuel.ai.rag.domain.KnowledgeDocument;
import github.lms.lemuel.ai.rag.domain.TextChunker;
import github.lms.lemuel.ai.chat.application.exception.AiNotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 지식 문서 적재 유스케이스 구현.
 *
 * <p><b>트랜잭션 경계는 ChatService 와 같은 규율을 따른다:</b>
 * 조회(무tx) → 마스킹·청킹(순수 계산) → 임베딩 API 호출(무tx) → 저장(단일 tx).
 * 임베딩은 청크 수만큼 외부 호출이라 수십 초가 걸릴 수 있는데, 그 사이 DB 커넥션을 붙들면
 * 풀이 고갈된다(ai-service 는 Hikari 최대 10). 그래서 {@code replaceDocument} 호출 시점에만 tx 를 연다.
 *
 * <p><b>PII 마스킹을 여기서도 하는 이유:</b> 청크는 프롬프트에 실려 외부 LLM 으로 나가는 새로운
 * 유출 경로다. 관리자가 넣는 내부 문서라 해도 계좌·카드번호가 섞일 수 있으므로 채팅 입력과
 * 같은 규칙을 통과시킨다. 마스킹 규칙을 rag 쪽에 복제하지 않고 {@link PiiMasker} 를 재사용한다 —
 * 규칙이 두 벌이 되면 한쪽만 갱신되는 것이 가장 나쁜 결과다.
 */
@Service
@ConditionalOnProperty(name = "app.ai.rag.enabled", havingValue = "true")
public class IngestKnowledgeService implements IngestKnowledgeUseCase, ListKnowledgeDocumentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestKnowledgeService.class);

    private final EmbeddingPort embeddingPort;
    private final KnowledgeBasePort knowledgeBasePort;
    private final RagProperties properties;
    private final Clock clock;

    public IngestKnowledgeService(EmbeddingPort embeddingPort,
                                 KnowledgeBasePort knowledgeBasePort,
                                 RagProperties properties,
                                 Clock clock) {
        this.embeddingPort = embeddingPort;
        this.knowledgeBasePort = knowledgeBasePort;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public IngestResult ingest(IngestCommand command) {
        if (!embeddingPort.isConfigured()) {
            // 채팅과 동일 정책 — 키가 없으면 503 로 안내하고 아무것도 저장하지 않는다.
            throw new AiNotConfiguredException();
        }

        String maskedContent = PiiMasker.mask(command.content());
        String contentHash = ContentHash.of(maskedContent);

        // 본문이 그대로면 재임베딩하지 않는다 (같은 문서 재적재의 비용을 0 으로).
        Optional<String> existingHash = knowledgeBasePort.findContentHash(command.sourceUri());
        if (existingHash.filter(contentHash::equals).isPresent()) {
            log.info("[RAG] 적재 스킵 — 본문 해시 동일: sourceUri={}", LogSafe.of(command.sourceUri()));
            return new IngestResult(command.sourceUri(), 0, true, embeddingPort.modelId());
        }

        List<String> chunkTexts = TextChunker.chunk(
                maskedContent, properties.chunkMaxChars(), properties.chunkOverlapChars());
        if (chunkTexts.isEmpty()) {
            // 2차 방어 — 공백만인 본문은 IngestCommand 가 이미 거부하므로 현재 도달 불가다.
            // 그래도 남겨 둔다: 청킹 규칙이 바뀌어 0청크가 나오는 날, 검색에 절대 잡히지 않는
            // 유령 행이 조용히 쌓이는 것보다 여기서 시끄럽게 실패하는 편이 낫다.
            throw new IllegalArgumentException("적재할 내용이 없습니다 (마스킹 후 본문이 비었습니다)");
        }

        // ── 트랜잭션 밖: 외부 임베딩 API ──
        List<Embedding> embeddings = embeddingPort.embedDocuments(chunkTexts);
        if (embeddings.size() != chunkTexts.size()) {
            // 순서·개수 대응이 깨지면 청크와 벡터가 어긋난 채 저장된다 — 저장 전에 막는다.
            throw new IllegalStateException("임베딩 개수가 청크 수와 다릅니다: chunks=" + chunkTexts.size()
                    + ", embeddings=" + embeddings.size());
        }

        List<EmbeddedChunk> chunks = new ArrayList<>(chunkTexts.size());
        for (int i = 0; i < chunkTexts.size(); i++) {
            chunks.add(new EmbeddedChunk(i, chunkTexts.get(i), embeddings.get(i), embeddingPort.modelId()));
        }

        // ── 단일 트랜잭션: 문서 + 청크 전량 교체 ──
        KnowledgeDocument document = KnowledgeDocument.ingest(
                command.title(), command.sourceUri(), contentHash, chunks.size(), clock.instant());
        knowledgeBasePort.replaceDocument(document, chunks);

        log.info("[RAG] 적재 완료: sourceUri={}, chunks={}, model={}",
                LogSafe.of(command.sourceUri()), chunks.size(), embeddingPort.modelId());
        return new IngestResult(command.sourceUri(), chunks.size(), false, embeddingPort.modelId());
    }

    /**
     * 적재 문서 목록 — 지식베이스를 건드리지 않는 순수 조회다.
     *
     * <p>여기에 필터·가공을 두지 않는다. 이 응답의 유일한 용도는 매니페스트(리포)와 실제 적재분(DB)의
     * <b>대조</b>이고, 대조 대상이 가공되면 diff 가 거짓말을 한다.
     */
    @Override
    public List<KnowledgeDocument> listDocuments() {
        return knowledgeBasePort.listDocuments();
    }

    @Override
    public boolean deleteBySourceUri(String sourceUri) {
        boolean deleted = knowledgeBasePort.deleteBySourceUri(sourceUri);
        log.info("[RAG] 삭제 요청: sourceUri={}, 삭제됨={}", LogSafe.of(sourceUri), deleted);
        return deleted;
    }
}
