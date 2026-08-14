package github.lms.lemuel.insurance.adapter.in.kafka.quarantine;

import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/** 보험 Kafka 소비 실패의 durable evidence. */
@Entity
@Table(name = "quarantined_events")
public class QuarantinedEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Column(name = "kafka_partition", nullable = false)
    private int kafkaPartition;

    @Column(name = "kafka_offset", nullable = false)
    private long kafkaOffset;

    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cause", nullable = false, length = 30)
    private ConsumedEventQuarantine.Cause cause;

    @Column(name = "cause_detail", columnDefinition = "text")
    private String causeDetail;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    protected QuarantinedEventJpaEntity() {
    }

    public QuarantinedEventJpaEntity(
            String consumerGroup,
            String topic,
            int kafkaPartition,
            long kafkaOffset,
            UUID eventId,
            ConsumedEventQuarantine.Cause cause,
            String causeDetail,
            String payload) {
        this.consumerGroup = consumerGroup;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.eventId = eventId;
        this.cause = cause;
        this.causeDetail = causeDetail;
        this.payload = payload;
        this.occurredAt = LocalDateTime.now();
    }
}
