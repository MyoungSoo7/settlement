package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

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

/**
 * 격리된 소비 이벤트 — "조용한 유실"을 대체하는 추적 레코드 (P0-3).
 *
 * <p>원본 payload·원인 상세를 증거로 보존하고, 운영자 재처리(replay) 시
 * {@code NEW → REPLAYED} 로만 전이한다. 행 삭제·격리 증거 수정은 없다(append + 상태 전이).
 */
@Entity
@Table(name = "quarantined_events")
public class QuarantinedEventJpaEntity {

    public enum Status { NEW, REPLAYED }

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "replay_event_id")
    private UUID replayEventId;

    protected QuarantinedEventJpaEntity() { }

    public QuarantinedEventJpaEntity(String consumerGroup, String topic, int kafkaPartition, long kafkaOffset,
                                     UUID eventId, ConsumedEventQuarantine.Cause cause,
                                     String causeDetail, String payload) {
        this.consumerGroup = consumerGroup;
        this.topic = topic;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.eventId = eventId;
        this.cause = cause;
        this.causeDetail = causeDetail;
        this.payload = payload;
        this.status = Status.NEW;
        this.occurredAt = LocalDateTime.now();
    }

    /** 재처리 완료 전이 — 사용한 event_id 를 증거로 남긴다. NEW 에서만 호출된다. */
    public void markReplayed(UUID usedEventId) {
        if (status != Status.NEW) {
            throw new IllegalStateException("이미 재처리된 격리 이벤트: id=" + id + ", status=" + status);
        }
        this.status = Status.REPLAYED;
        this.replayEventId = usedEventId;
        this.resolvedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getConsumerGroup() { return consumerGroup; }
    public String getTopic() { return topic; }
    public int getKafkaPartition() { return kafkaPartition; }
    public long getKafkaOffset() { return kafkaOffset; }
    public UUID getEventId() { return eventId; }
    public ConsumedEventQuarantine.Cause getCause() { return cause; }
    public String getCauseDetail() { return causeDetail; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public UUID getReplayEventId() { return replayEventId; }
}
