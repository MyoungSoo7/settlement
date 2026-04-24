package github.lms.lemuel.rag.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.rag.adapter.in.web.dto.RagQueryRequest;
import github.lms.lemuel.rag.adapter.in.web.dto.RagStreamEvent;
import github.lms.lemuel.rag.application.port.in.EmbeddingIndexUseCase;
import github.lms.lemuel.rag.application.port.in.RagQueryUseCase;
import github.lms.lemuel.rag.application.port.out.ConversationPort;
import github.lms.lemuel.rag.domain.Conversation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagQueryUseCase ragQueryUseCase;
    private final EmbeddingIndexUseCase embeddingIndexUseCase;
    private final ConversationPort conversationPort;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(@Valid @RequestBody RagQueryRequest request) {
        SseEmitter emitter = new SseEmitter(60_000L);

        executor.execute(() -> {
            try {
                ragQueryUseCase.query(request.sessionId(), request.question(), token -> {
                    try {
                        String json = objectMapper.writeValueAsString(RagStreamEvent.token(token));
                        emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        log.error("SSE 전송 실패", e);
                    }
                });

                String doneJson = objectMapper.writeValueAsString(RagStreamEvent.finished());
                emitter.send(SseEmitter.event().data(doneJson, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.error("RAG 질의 실패: sessionId={}", request.sessionId(), e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @PostMapping("/sessions")
    public ResponseEntity<Map<String, String>> createSession() {
        String sessionId = UUID.randomUUID().toString();
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Conversation> getConversation(@PathVariable String sessionId) {
        return ResponseEntity.ok(conversationPort.getConversation(sessionId));
    }

    @PostMapping("/index")
    public ResponseEntity<EmbeddingIndexUseCase.IndexResult> indexAll() {
        return ResponseEntity.ok(embeddingIndexUseCase.indexAll());
    }
}
