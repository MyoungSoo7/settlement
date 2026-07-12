package github.lms.lemuel.settlement.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_adjustments")
@Getter @Setter
@NoArgsConstructor
public class SettlementAdjustmentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    /** V25 에서 nullable 로 완화. Chargeback 경로는 NULL. */
    @Column(name = "refund_id")
    private Long refundId;

    /** V44 — 카드사 분쟁 연결. refund_id 와 양립 (둘 중 하나만 채워짐). */
    @Column(name = "chargeback_id")
    private Long chargebackId;

    /** PG 대사 승인 clawback 연결. refund_id/chargeback_id 와 배타 (한 row 는 최대 한 출처). */
    @Column(name = "reconciliation_discrepancy_id")
    private Long reconciliationDiscrepancyId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "adjustment_date", nullable = false)
    private LocalDate adjustmentDate;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
