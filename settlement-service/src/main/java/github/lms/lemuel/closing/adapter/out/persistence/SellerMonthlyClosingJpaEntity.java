package github.lms.lemuel.closing.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 셀러 월 정산 마트 행 영속 엔티티 — {@code seller_monthly_closings}.
 *
 * <p>(period_ym, seller_id) 유니크. 재마감은 기간 단위 전체 교체(delete+insert)라
 * 행 단위 갱신은 없다 — INSERT 전용 성격.
 */
@Entity
@Table(name = "seller_monthly_closings")
@Getter
@Setter
@NoArgsConstructor
public class SellerMonthlyClosingJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_ym", nullable = false, length = 7)
    private String periodYm;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "settlement_count", nullable = false)
    private long settlementCount;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "commission_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "holdback_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal holdbackAmount;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal netAmount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
}
