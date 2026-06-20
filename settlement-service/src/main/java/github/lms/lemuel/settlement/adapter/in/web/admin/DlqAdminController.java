package github.lms.lemuel.settlement.adapter.in.web.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.settlement.adapter.in.kafka.DlqReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * DLT 운영자 콘솔.
 *
 * <p>인가는 {@code SecurityConfig} 의 {@code /admin/dlq/**} 매핑으로 ROLE_ADMIN 강제.
 * 모든 작업은 V34 audit_logs 에 기록 — operator, topic, count 추적.
 *
 * <p>{@code app.kafka.enabled=true} 일 때만 노출 — 카프카 비활성 환경에서는 빈 자체가 만들어지지 않음.
 */
@Tag(name = "DLQ Admin", description = "Kafka Dead Letter Topic 검사·재처리")
@RestController
@RequestMapping("/admin/dlq")
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class DlqAdminController {

    private static final Logger log = LoggerFactory.getLogger(DlqAdminController.class);

    private final DlqReplayService dlqReplayService;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public DlqAdminController(DlqReplayService dlqReplayService,
                               AuditLogger auditLogger,
                               ObjectMapper objectMapper) {
        this.dlqReplayService = dlqReplayService;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "DLT 메시지 인스펙션 (commit 없이 read-only)")
    @GetMapping("/inspect")
    public ResponseEntity<List<DlqReplayService.DlqMessage>> inspect(
            @RequestParam String topic,
            @RequestParam(defaultValue = "20") int max) {
        List<DlqReplayService.DlqMessage> messages = dlqReplayService.inspect(topic, max);

        auditLogger.record(
                AuditAction.DLQ_INSPECTED,
                "DltTopic",
                topic,
                toJson(Map.of("operator", currentOperator(), "topic", topic,
                        "limit", max, "found", messages.size()))
        );
        log.info("[DLQ inspect] operator={}, topic={}, found={}",
                currentOperator(), topic, messages.size());
        return ResponseEntity.ok(messages);
    }

    @Operation(summary = "DLT 메시지 → 원본 토픽 재처리. 멱등 (processed_events 로 중복 차단)")
    @PostMapping("/replay")
    public ResponseEntity<DlqReplayService.ReplayResult> replay(
            @RequestParam String topic,
            @RequestParam(defaultValue = "10") int max) {
        DlqReplayService.ReplayResult result = dlqReplayService.replay(topic, max);

        auditLogger.record(
                AuditAction.DLQ_REPLAYED,
                "DltTopic",
                topic,
                toJson(Map.of("operator", currentOperator(),
                        "dltTopic", result.dltTopic(),
                        "sourceTopic", result.sourceTopic(),
                        "sent", result.sent(),
                        "skipped", result.skipped()))
        );
        log.warn("[DLQ replay] operator={}, dltTopic={}, sourceTopic={}, sent={}, skipped={}",
                currentOperator(), result.dltTopic(), result.sourceTopic(),
                result.sent(), result.skipped());
        return ResponseEntity.ok(result);
    }

    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "anonymous" : auth.getName();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"audit_serialization_failed\"}";
        }
    }
}
