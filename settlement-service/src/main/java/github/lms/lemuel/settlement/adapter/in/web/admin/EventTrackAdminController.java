package github.lms.lemuel.settlement.adapter.in.web.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.audit.application.AuditLogger;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.settlement.adapter.in.kafka.quarantine.DuplicateEventRepository;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.in.kafka.quarantine.QuarantineReplayService;
import github.lms.lemuel.settlement.adapter.in.kafka.quarantine.QuarantinedEventJpaEntity;
import github.lms.lemuel.settlement.adapter.in.kafka.quarantine.QuarantinedEventRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 소비 이벤트 3분류(정상·중복·격리) 추적 콘솔 (P0-3 AC4).
 *
 * <p>인가는 {@code SecurityConfig} 의 {@code /admin/event-track/**} 매핑으로 ROLE_ADMIN 강제.
 * 조회는 kafka 비활성 환경에서도 유효(데이터는 DB) — 재처리만 {@link QuarantineReplayService}
 * 가용 시(kafka.enabled=true) 동작하고, 아니면 503 을 돌려준다.
 */
@Tag(name = "Event Track Admin", description = "소비 이벤트 정상·중복·격리 추적 + 격리 재처리")
@RestController
@RequestMapping("/admin/event-track")
public class EventTrackAdminController {

    private static final Logger log = LoggerFactory.getLogger(EventTrackAdminController.class);

    private final ProcessedEventRepository processedEventRepository;
    private final DuplicateEventRepository duplicateEventRepository;
    private final QuarantinedEventRepository quarantinedEventRepository;
    private final ObjectProvider<QuarantineReplayService> replayService;
    private final AuditLogger auditLogger;
    private final ObjectMapper objectMapper;

    public EventTrackAdminController(ProcessedEventRepository processedEventRepository,
                                     DuplicateEventRepository duplicateEventRepository,
                                     QuarantinedEventRepository quarantinedEventRepository,
                                     ObjectProvider<QuarantineReplayService> replayService,
                                     AuditLogger auditLogger,
                                     ObjectMapper objectMapper) {
        this.processedEventRepository = processedEventRepository;
        this.duplicateEventRepository = duplicateEventRepository;
        this.quarantinedEventRepository = quarantinedEventRepository;
        this.replayService = replayService;
        this.auditLogger = auditLogger;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "3분류 요약 — 정상 처리·중복 재도착·격리(NEW/REPLAYED) 건수")
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "processed", processedEventRepository.count(),
                "duplicateHits", duplicateEventRepository.totalHits(),
                "quarantinedNew", quarantinedEventRepository.countByStatus(QuarantinedEventJpaEntity.Status.NEW),
                "quarantinedReplayed", quarantinedEventRepository.countByStatus(QuarantinedEventJpaEntity.Status.REPLAYED));
    }

    @Operation(summary = "격리 이벤트 목록 (최근 100건)")
    @GetMapping("/quarantined")
    public List<QuarantinedEventView> quarantined(
            @RequestParam(defaultValue = "NEW") QuarantinedEventJpaEntity.Status status) {
        return quarantinedEventRepository.findTop100ByStatusOrderByOccurredAtDesc(status).stream()
                .map(QuarantinedEventView::from)
                .toList();
    }

    @Operation(summary = "격리 이벤트 재처리 — 격리된 원본 payload 를 원본 토픽에 republish (멱등은 processed_events 가 보장)")
    @PostMapping("/quarantined/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable long id) {
        QuarantineReplayService service = replayService.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "kafka 비활성 환경 — 재처리는 app.kafka.enabled=true 에서만 가능"));
        }
        // payload override 미제공 — 격리된 원본 바이트만 재발행한다(임의 payload 로 상류 토픽에
        // 이벤트를 위조하던 벡터 제거). event_id 는 서버가 결정적으로 부여(멱등은 processed_events).
        UUID usedEventId = service.replay(id);
        auditLogger.record(
                AuditAction.QUARANTINE_REPLAYED,
                "QuarantinedEvent",
                String.valueOf(id),
                toJson(Map.of("operator", currentOperator(),
                        "quarantineId", id,
                        "usedEventId", usedEventId.toString()))
        );
        log.warn("[event-track] 격리 재처리. operator={}, id={}, usedEventId={}", currentOperator(), id, usedEventId);
        return ResponseEntity.ok(Map.of("quarantineId", id, "usedEventId", usedEventId.toString()));
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

    public record QuarantinedEventView(Long id, String consumerGroup, String topic, int partition, long offset,
                                       String eventId, String cause, String causeDetail, String payloadPreview,
                                       String status, LocalDateTime occurredAt, LocalDateTime resolvedAt,
                                       String replayEventId) {
        private static final int PAYLOAD_PREVIEW_LIMIT = 500;

        static QuarantinedEventView from(QuarantinedEventJpaEntity e) {
            String payload = e.getPayload();
            String preview = payload == null ? null
                    : (payload.length() > PAYLOAD_PREVIEW_LIMIT ? payload.substring(0, PAYLOAD_PREVIEW_LIMIT) + "..." : payload);
            return new QuarantinedEventView(
                    e.getId(), e.getConsumerGroup(), e.getTopic(), e.getKafkaPartition(), e.getKafkaOffset(),
                    e.getEventId() == null ? null : e.getEventId().toString(),
                    e.getCause().name(), e.getCauseDetail(), preview,
                    e.getStatus().name(), e.getOccurredAt(), e.getResolvedAt(),
                    e.getReplayEventId() == null ? null : e.getReplayEventId().toString());
        }
    }
}
