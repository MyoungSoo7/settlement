package github.lms.lemuel.common.outbox.domain;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Outbox 이벤트 도메인 모델 (순수 POJO).
 *
 * 비즈니스 변경과 동일한 트랜잭션 안에서 PENDING 상태로 기록되고,
 * OutboxPublisherScheduler 가 주기적으로 읽어 외부 시스템에 발행 후 PUBLISHED 로 전이시킨다.
 */
public class OutboxEvent {

    private final Long id;
    private final String aggregateType;
    private final String aggregateId;
    private final String eventType;
    private final UUID eventId;
    private final String payload;
    private OutboxEventStatus status;
    private int retryCount;
    private String lastError;
    private final LocalDateTime createdAt;
    private LocalDateTime publishedAt;

    private OutboxEvent(Long id,
                        String aggregateType,
                        String aggregateId,
                        String eventType,
                        UUID eventId,
                        String payload,
                        OutboxEventStatus status,
                        int retryCount,
                        String lastError,
                        LocalDateTime createdAt,
                        LocalDateTime publishedAt) {
        this.id = id;
        this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
        this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.status = Objects.requireNonNull(status, "status");
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.publishedAt = publishedAt;
    }

    /**
     * 신규 PENDING 이벤트 생성.
     */
    public static OutboxEvent pending(String aggregateType, String aggregateId,
                                      String eventType, String payload) {
        return new OutboxEvent(
                null,
                aggregateType,
                aggregateId,
                eventType,
                UUID.randomUUID(),
                payload,
                OutboxEventStatus.PENDING,
                0,
                null,
                LocalDateTime.now(),
                null
        );
    }

    /**
     * 영속 상태에서 재구성 (어댑터에서만 사용).
     */
    public static OutboxEvent rehydrate(Long id, String aggregateType, String aggregateId,
                                        String eventType, UUID eventId, String payload,
                                        OutboxEventStatus status, int retryCount, String lastError,
                                        LocalDateTime createdAt, LocalDateTime publishedAt) {
        return new OutboxEvent(id, aggregateType, aggregateId, eventType, eventId, payload,
                status, retryCount, lastError, createdAt, publishedAt);
    }

    public void markPublished() {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = error;
        // 재시도 한계는 스케줄러에서 판단 — 여기선 상태만 갱신
        if (this.retryCount >= 10) {
            this.status = OutboxEventStatus.FAILED;
        }
    }

    public boolean isPending() {
        return status == OutboxEventStatus.PENDING;
    }

    // Getters
    public Long getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public UUID getEventId() { return eventId; }
    public String getPayload() { return payload; }
    public OutboxEventStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
}
