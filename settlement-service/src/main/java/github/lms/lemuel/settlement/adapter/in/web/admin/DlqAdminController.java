package github.lms.lemuel.settlement.adapter.in.web.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.log.LogSafe;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.settlement.adapter.in.kafka.DlqReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
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

    private static final String DLT_SUFFIX = ".DLT";

    private final DlqReplayService dlqReplayService;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    public DlqAdminController(DlqReplayService dlqReplayService,
                               AuditLogger auditLogger,
                               ObjectMapper objectMapper,
                               KafkaListenerEndpointRegistry listenerRegistry) {
        this.dlqReplayService = dlqReplayService;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
        this.listenerRegistry = listenerRegistry;
    }

    /**
     * 이 서비스가 실제로 구독 중인 토픽에서 DLT 후보를 만든다.
     *
     * <p>토픽 이름을 사람이 외워 넣게 하면 오타 하나가 "DLT 가 비어 있다"로 보여 장애를 놓친다.
     * 목록을 코드나 화면에 박아 두는 방법도 있지만 컨슈머가 늘 때마다 조용히 어긋나므로,
     * <b>런타임 구독 목록</b>을 정본으로 삼는다.
     *
     * <p>DLT 토픽 자체를 구독하는 컨테이너가 있으면 후보에서 뺀다 — {@code .DLT.DLT} 는 존재하지 않는다.
     */
    @Operation(summary = "DLT 후보 토픽 목록 — 구독 중인 원본 토픽에서 파생")
    @GetMapping("/topics")
    public ResponseEntity<List<DltTopic>> topics() {
        List<DltTopic> topics = listenerRegistry.getListenerContainers().stream()
                .flatMap(container -> {
                    String[] subscribed = container.getContainerProperties().getTopics();
                    return subscribed == null ? java.util.stream.Stream.<String>empty()
                            : java.util.Arrays.stream(subscribed);
                })
                .filter(topic -> topic != null && !topic.endsWith(DLT_SUFFIX))
                .distinct()
                .sorted()
                .map(topic -> new DltTopic(topic, topic + DLT_SUFFIX))
                .toList();
        return ResponseEntity.ok(topics);
    }

    /** 원본 토픽과 그 DLT 이름 쌍 — 화면이 이름을 조립하지 않게 서버가 완성해 준다. */
    public record DltTopic(String sourceTopic, String dltTopic) { }

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
                LogSafe.of(currentOperator()), LogSafe.of(topic), messages.size());
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
