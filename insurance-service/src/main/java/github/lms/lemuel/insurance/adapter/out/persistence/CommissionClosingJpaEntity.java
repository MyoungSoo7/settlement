package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.domain.CommissionClosing;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * commission_closings 테이블 매핑 (V6) — append-only 마감 스냅샷.
 *
 * <p>@Version 없음 — UPDATE 자체가 트리거로 차단되는 INSERT 전용 테이블이다.
 * closing_month 는 DB 에서 DATE(월 1일 정규화 CHECK), 도메인에서는 {@link YearMonth}.
 */
@Entity
@Table(name = "commission_closings", schema = "opslab")
public class CommissionClosingJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "closing_id", nullable = false)
    private UUID closingId;

    @Column(name = "fc_id", nullable = false, length = 64)
    private String fcId;

    @Column(name = "closing_month", nullable = false)
    private LocalDate closingMonth;

    @Column(name = "total_paid_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalPaidAmount;

    @Column(name = "installment_count", nullable = false)
    private int installmentCount;

    @Column(name = "closed_at", insertable = false, updatable = false)
    private Instant closedAt;

    protected CommissionClosingJpaEntity() {
    }

    public static CommissionClosingJpaEntity fromDomain(CommissionClosing c) {
        CommissionClosingJpaEntity e = new CommissionClosingJpaEntity();
        e.closingId = UUID.fromString(c.getClosingId());
        e.fcId = c.getFcId();
        e.closingMonth = c.getClosingMonth().atDay(1);
        e.totalPaidAmount = c.getTotalPaidAmount();
        e.installmentCount = Math.toIntExact(c.getInstallmentCount());
        return e;
    }

    public CommissionClosing toDomain() {
        return CommissionClosing.rehydrate(
                id,
                closingId.toString(),
                fcId,
                YearMonth.from(closingMonth),
                totalPaidAmount,
                installmentCount);
    }
}
