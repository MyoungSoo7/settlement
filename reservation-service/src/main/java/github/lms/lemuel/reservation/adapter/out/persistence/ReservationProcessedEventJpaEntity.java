package github.lms.lemuel.reservation.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 컨슈머 멱등 기록 (consumer_group, event_id). reservation-service 자체 DB 소유.
 */
@Entity
@Table(name = "processed_events")
@IdClass(ReservationProcessedEventJpaEntity.Pk.class)
public class ReservationProcessedEventJpaEntity {

    @Id
    @Column(name = "consumer_group", length = 100)
    private String consumerGroup;

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    protected ReservationProcessedEventJpaEntity() {
    }

    public ReservationProcessedEventJpaEntity(String consumerGroup, UUID eventId, String eventType) {
        this.consumerGroup = consumerGroup;
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = LocalDateTime.now();
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public UUID getEventId() {
        return eventId;
    }

    /** 복합키 클래스. */
    public static class Pk implements Serializable {
        private String consumerGroup;
        private UUID eventId;

        public Pk() {
        }

        public Pk(String consumerGroup, UUID eventId) {
            this.consumerGroup = consumerGroup;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(consumerGroup, pk.consumerGroup) && Objects.equals(eventId, pk.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumerGroup, eventId);
        }
    }
}
