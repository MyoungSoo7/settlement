package github.lms.lemuel.settlement.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_index_queue")
@Getter @Setter
@NoArgsConstructor
public class SettlementIndexQueueJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(nullable = false, length = 20)
    private String operation;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

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

    public SettlementIndexQueueJpaEntity(Long settlementId, String operation) {
        this.settlementId = settlementId;
        this.operation = operation;
        // 지수 백오프: 첫 재시도는 1분 후
        this.nextRetryAt = LocalDateTime.now().plusMinutes(1);
    }
}
