package github.lms.lemuel.closing.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 정보계 월마감 run 영속 엔티티 — {@code monthly_closing_runs}.
 *
 * <p>기간(period_ym) 유니크 — 재마감 시 같은 행을 upsert 한다(최신 run 만 유지).
 * FAILED run 은 합계 스냅샷(total_*) 이 null 이다.
 */
@Entity
@Table(name = "monthly_closing_runs")
@Getter
@Setter
@NoArgsConstructor
public class MonthlyClosingRunJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** YearMonth.toString() → "YYYY-MM". */
    @Column(name = "period_ym", nullable = false, length = 7, unique = true)
    private String periodYm;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "triggered_by", nullable = false, length = 100)
    private String triggeredBy;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "seller_count", nullable = false)
    private int sellerCount;

    @Column(name = "settlement_count", nullable = false)
    private long settlementCount;

    @Column(name = "unmapped_count", nullable = false)
    private long unmappedCount;

    @Column(name = "pending_count", nullable = false)
    private long pendingCount;

    @Column(name = "total_gross", precision = 18, scale = 2)
    private BigDecimal totalGross;

    @Column(name = "total_refunded", precision = 18, scale = 2)
    private BigDecimal totalRefunded;

    @Column(name = "total_commission", precision = 18, scale = 2)
    private BigDecimal totalCommission;

    @Column(name = "total_holdback", precision = 18, scale = 2)
    private BigDecimal totalHoldback;

    @Column(name = "total_net", precision = 18, scale = 2)
    private BigDecimal totalNet;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}
