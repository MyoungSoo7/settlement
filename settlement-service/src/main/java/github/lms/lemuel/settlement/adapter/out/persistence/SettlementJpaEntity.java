package github.lms.lemuel.settlement.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Settlement JPA Entity (인프라 레이어, 도메인과 분리)
 * DB 스키마: id, payment_id, order_id, payment_amount, commission, net_amount, status, settlement_date, confirmed_at, created_at, updated_at
 */
@Entity
@Table(
        name = "settlements",
        // 정산 멱등성 3단 방어 중 스키마 계층 — 같은 결제는 정산 1건만 존재.
        // prod 스키마는 Flyway V3 가 동일 제약을 소유하며, 이 선언은 entity 기반
        // 스키마 생성(테스트 create-drop) 시에도 제약이 재현되도록 명시한 것이다.
        uniqueConstraints = @UniqueConstraint(name = "uk_settlements_payment_id", columnNames = "payment_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "payment_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal paymentAmount;

    @Column(name = "refunded_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal commission;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate = new BigDecimal("0.0300");

    @Column(name = "net_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal netAmount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    // ========== 정산 보류 (Holdback) — V42 ==========

    @Column(name = "holdback_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal holdbackAmount = BigDecimal.ZERO;

    @Column(name = "holdback_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal holdbackRate = BigDecimal.ZERO;

    @Column(name = "holdback_release_date")
    private LocalDate holdbackReleaseDate;

    @Column(name = "holdback_released", nullable = false)
    private boolean holdbackReleased = false;

    @Column(name = "holdback_released_at")
    private LocalDateTime holdbackReleasedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
