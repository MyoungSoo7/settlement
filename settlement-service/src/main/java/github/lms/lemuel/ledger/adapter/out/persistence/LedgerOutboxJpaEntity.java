package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 원장 아웃박스 row — V49 opslab.ledger_outbox 매핑.
 */
@Entity
@Table(name = "ledger_outbox")
@Getter
@Setter
@NoArgsConstructor
public class LedgerOutboxJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** LedgerTaskType.name() — V49 chk_ledger_outbox_task_type 참조. */
    @Column(name = "task_type", nullable = false, length = 30)
    private String taskType;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Column(name = "refund_id")
    private Long refundId;

    @Column(name = "refund_amount", precision = 14, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "adjustment_date")
    private LocalDate adjustmentDate;

    /** LedgerOutboxStatus.name() — V49 chk_ledger_outbox_status 참조. */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
