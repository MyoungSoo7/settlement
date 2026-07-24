package github.lms.lemuel.ledger.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 원장 회계 기간(월) 영속 엔티티 — {@code ledger_periods}.
 *
 * <p>도메인 {@code LedgerPeriod} 와 분리된 어댑터 레이어 매핑. period_ym 은 "YYYY-MM" CHAR(7),
 * 마감 시 status/closed_at/closed_by/total_debit/total_credit 가 채워진다(OPEN 시 스냅샷 null).
 */
@Entity
@Table(name = "ledger_periods")
@Getter
@Setter
@NoArgsConstructor
public class LedgerPeriodJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** YearMonth.toString() → "YYYY-MM". */
    @Column(name = "period_ym", nullable = false, length = 7, unique = true)
    private String periodYm;

    /** LedgerPeriodStatus.name() 저장 — chk_ledger_period_status 참조. */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 100)
    private String closedBy;

    @Column(name = "total_debit", precision = 18, scale = 2)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", precision = 18, scale = 2)
    private BigDecimal totalCredit;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
