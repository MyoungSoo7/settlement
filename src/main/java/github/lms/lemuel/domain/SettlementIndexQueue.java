package github.lms.lemuel.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Elasticsearch 색인 실패 시 재시도를 위한 큐
 */
@Entity
@Table(name = "settlement_index_queue")
@Getter
@Setter
public class SettlementIndexQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "operation", nullable = false, length = 20)
    private String operation; // INDEX, UPDATE, DELETE

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING"; // PENDING, PROCESSING, SUCCESS, FAILED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementRetry() {
        this.retryCount++;
        // 지수 백오프: 1분, 5분, 15분
        int minutesToWait = (int) Math.pow(5, retryCount - 1);
        this.nextRetryAt = LocalDateTime.now().plusMinutes(minutesToWait);
    }

    public boolean canRetry() {
        return retryCount < maxRetries;
    }
}
