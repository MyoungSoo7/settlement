package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 이미 처리된 event_id 의 재도착(중복) 추적 — 3분류 조회의 DUPLICATE 축 (P0-3). */
@Entity
@Table(name = "duplicate_events")
public class DuplicateEventJpaEntity {

    @EmbeddedId
    private DuplicateEventId id;

    @Column(name = "topic", nullable = false, length = 200)
    private String topic;

    @Column(name = "hit_count", nullable = false)
    private long hitCount;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    protected DuplicateEventJpaEntity() { }

    public DuplicateEventJpaEntity(String consumerGroup, UUID eventId, String topic) {
        this.id = new DuplicateEventId(consumerGroup, eventId);
        this.topic = topic;
        this.hitCount = 1;
        this.firstSeenAt = LocalDateTime.now();
        this.lastSeenAt = this.firstSeenAt;
    }

    /** 같은 event_id 재도착 1회를 기록한다. */
    public void recordHit() {
        this.hitCount++;
        this.lastSeenAt = LocalDateTime.now();
    }

    public DuplicateEventId getId() { return id; }
    public String getTopic() { return topic; }
    public long getHitCount() { return hitCount; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }

    @Embeddable
    public static class DuplicateEventId implements Serializable {
        @Column(name = "consumer_group", nullable = false, length = 100)
        private String consumerGroup;

        @Column(name = "event_id", nullable = false)
        private UUID eventId;

        protected DuplicateEventId() { }

        public DuplicateEventId(String consumerGroup, UUID eventId) {
            this.consumerGroup = consumerGroup;
            this.eventId = eventId;
        }

        public String getConsumerGroup() { return consumerGroup; }
        public UUID getEventId() { return eventId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DuplicateEventId that)) return false;
            return Objects.equals(consumerGroup, that.consumerGroup) && Objects.equals(eventId, that.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, eventId);
        }
    }
}
