package github.lms.lemuel.ai.rag.adapter.in.web;

import github.lms.lemuel.ai.rag.adapter.in.web.dto.KnowledgeDtos.DocumentsResponse;
import github.lms.lemuel.ai.rag.adapter.in.web.dto.KnowledgeDtos.IngestRequest;
import github.lms.lemuel.ai.rag.adapter.in.web.dto.KnowledgeDtos.IngestResponse;
import github.lms.lemuel.ai.rag.adapter.in.web.dto.KnowledgeDtos.SearchResponse;
import github.lms.lemuel.ai.rag.application.port.in.IngestKnowledgeUseCase;
import github.lms.lemuel.ai.rag.application.port.in.IngestKnowledgeUseCase.IngestCommand;
import github.lms.lemuel.ai.rag.application.port.in.ListKnowledgeDocumentsUseCase;
import github.lms.lemuel.ai.rag.application.port.in.SearchKnowledgeUseCase;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 지식베이스 API.
 *
 * <ul>
 *   <li>POST   /api/ai/knowledge/documents — 문서 적재/교체 (<b>ADMIN</b>)</li>
 *   <li>GET    /api/ai/knowledge/documents — 적재 문서 목록 (<b>ADMIN</b>)</li>
 *   <li>DELETE /api/ai/knowledge/documents?sourceUri=… — 문서 삭제 (<b>ADMIN</b>)</li>
 *   <li>GET    /api/ai/knowledge/search?q=… — 검색 확인 (USER 이상)</li>
 * </ul>
 *
 * <p><b>적재를 ADMIN 으로 제한하는 이유:</b> 여기 들어간 텍스트는 이후 <b>모든 사용자</b>의 답변
 * 근거로 프롬프트에 실린다. 즉 적재는 곧 챗봇의 발언 내용에 대한 쓰기 권한이다(프롬프트 인젝션의
 * 영구 저장소가 될 수 있다). 권한 강제는 {@code AiSecurityConfig} 의 경로 매처가 담당한다.
 *
 * <p>검색 API 를 USER 에게 열어 두는 것은 진단 목적이다 — 답변이 이상할 때 검색이 무엇을
 * 찾았는지 봐야 원인을 검색 단계와 LLM 단계로 나눌 수 있다.
 */
@RestController
@RequestMapping("/api/ai/knowledge")
@ConditionalOnProperty(name = "app.ai.rag.enabled", havingValue = "true")
public class KnowledgeController {

    private final IngestKnowledgeUseCase ingestKnowledgeUseCase;
    private final ListKnowledgeDocumentsUseCase listKnowledgeDocumentsUseCase;
    private final SearchKnowledgeUseCase searchKnowledgeUseCase;

    public KnowledgeController(IngestKnowledgeUseCase ingestKnowledgeUseCase,
                              ListKnowledgeDocumentsUseCase listKnowledgeDocumentsUseCase,
                              SearchKnowledgeUseCase searchKnowledgeUseCase) {
        this.ingestKnowledgeUseCase = ingestKnowledgeUseCase;
        this.listKnowledgeDocumentsUseCase = listKnowledgeDocumentsUseCase;
        this.searchKnowledgeUseCase = searchKnowledgeUseCase;
    }

    @PostMapping("/documents")
    public IngestResponse ingest(@Valid @RequestBody IngestRequest request) {
        return IngestResponse.from(ingestKnowledgeUseCase.ingest(
                new IngestCommand(request.title(), request.sourceUri(), request.content())));
    }

    /**
     * 적재 문서 목록.
     *
     * <p>권한은 {@code AiSecurityConfig} 의 경로 매처가 그대로 준다 — 매처가 HTTP 메서드를 구분하지
     * 않으므로 {@code /documents} 에 대한 GET 도 ADMIN 이다. 이것이 옳다: 목록은 "챗봇이 무엇을 근거로
     * 말하는가"의 전체 목차이며, 적재 권한이 없는 사람이 알아야 할 정보가 아니다.
     */
    @GetMapping("/documents")
    public DocumentsResponse listDocuments() {
        return DocumentsResponse.of(listKnowledgeDocumentsUseCase.listDocuments());
    }

    @DeleteMapping("/documents")
    public ResponseEntity<Void> delete(@RequestParam String sourceUri) {
        return ingestKnowledgeUseCase.deleteBySourceUri(sourceUri)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/search")
    public SearchResponse search(@RequestParam("q") String query,
                                 @RequestParam(value = "topK", required = false) Integer topK) {
        return SearchResponse.of(query, searchKnowledgeUseCase.search(query, topK));
    }
}
