package github.lms.lemuel.ai.chat.adapter.in.web;

import github.lms.lemuel.ai.chat.adapter.in.web.dto.ChatDtos.ConversationDetailResponse;
import github.lms.lemuel.ai.chat.adapter.in.web.dto.ChatDtos.ConversationListResponse;
import github.lms.lemuel.ai.audit.application.port.out.RecordAuditPort;
import github.lms.lemuel.ai.chat.application.port.in.ConversationQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** 대화 이력 API (설계 §5.2) — 요청자 소유 대화만, 타인 것은 404. */
@RestController
@RequestMapping("/api/ai/conversations")
public class ConversationController {

    private final ConversationQuery conversationQuery;
    private final RecordAuditPort recordAuditPort;

    public ConversationController(ConversationQuery conversationQuery,
                                  RecordAuditPort recordAuditPort) {
        this.conversationQuery = conversationQuery;
        this.recordAuditPort = recordAuditPort;
    }

    @GetMapping
    public ConversationListResponse list(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         Authentication authentication) {
        Long userId = AuthenticatedUser.userId(authentication);
        return ConversationListResponse.from(conversationQuery.list(userId, page, size));
    }

    @GetMapping("/{id}")
    public ConversationDetailResponse get(@PathVariable UUID id, Authentication authentication) {
        Long userId = AuthenticatedUser.userId(authentication);
        return ConversationDetailResponse.from(conversationQuery.get(userId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        Long userId = AuthenticatedUser.userId(authentication);
        conversationQuery.delete(userId, id);
        recordAuditPort.record("CONVERSATION_DELETED", "Conversation", id.toString(), Map.of());
        return ResponseEntity.noContent().build();
    }
}
