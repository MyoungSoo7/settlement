package github.lms.lemuel.reservation.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.reservation.adapter.out.persistence.ReservationProcessedEventJpaEntity;
import github.lms.lemuel.reservation.adapter.out.persistence.SpringDataReservationProcessedEventRepository;
import github.lms.lemuel.reservation.adapter.out.persistence.SpringDataTechnicianViewRepository;
import github.lms.lemuel.reservation.adapter.out.persistence.TechnicianViewJpaEntity;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * user-service 멤버십/역할 변경 이벤트 → 로컬 기사 프로젝션(technician_view) upsert.
 *
 * <p>멱등: (consumer_group, event_id) 로 중복 처리 방지.
 * 페이로드: {userId, role, membershipStatus, active}.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class UserMembershipEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserMembershipEventConsumer.class);
    private static final String CONSUMER_GROUP = "lemuel-reservation";

    private final SpringDataTechnicianViewRepository technicianViewRepository;
    private final SpringDataReservationProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public UserMembershipEventConsumer(SpringDataTechnicianViewRepository technicianViewRepository,
                                       SpringDataReservationProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        this.technicianViewRepository = technicianViewRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.user-membership-changed}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onUserMembershipChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        UUID eventId = extractEventId(record);
        if (eventId == null) {
            log.warn("Skipping record without event_id header. topic={}, offset={}", record.topic(), record.offset());
            ack.acknowledge();
            return;
        }

        ReservationProcessedEventJpaEntity.Pk key =
                new ReservationProcessedEventJpaEntity.Pk(CONSUMER_GROUP, eventId);
        if (processedEventRepository.existsById(key)) {
            log.info("Event already processed, skipping. eventId={}", eventId);
            ack.acknowledge();
            return;
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload, eventId=" + eventId, e);
        }

        if (!node.hasNonNull("userId")) {
            throw new IllegalArgumentException("Missing userId, eventId=" + eventId);
        }
        Long userId = node.get("userId").asLong();

        TechnicianViewJpaEntity view = technicianViewRepository.findById(userId)
                .orElseGet(TechnicianViewJpaEntity::new);
        view.setUserId(userId);
        view.setRole(text(node, "role", "USER"));
        view.setMembershipStatus(text(node, "membershipStatus", "APPROVED"));
        view.setActive(!node.has("active") || node.get("active").asBoolean(true));
        view.setUpdatedAt(LocalDateTime.now());
        technicianViewRepository.save(view);

        processedEventRepository.save(
                new ReservationProcessedEventJpaEntity(CONSUMER_GROUP, eventId, "UserMembershipChanged"));

        log.info("technician_view upserted. userId={}, role={}, status={}",
                userId, view.getRole(), view.getMembershipStatus());
        ack.acknowledge();
    }

    private static String text(JsonNode node, String field, String defaultValue) {
        return node.hasNonNull(field) ? node.get(field).asText() : defaultValue;
    }

    private static UUID extractEventId(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("event_id");
        if (header == null) return null;
        try {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
