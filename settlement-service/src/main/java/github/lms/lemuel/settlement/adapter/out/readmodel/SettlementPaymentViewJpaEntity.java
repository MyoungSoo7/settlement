package github.lms.lemuel.settlement.adapter.out.readmodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * settlement 소유 결제 프로젝션 (ADR 0020 Phase 2).
 *
 * <p>order 의 payments 테이블을 @Immutable 로 직접 매핑하던 {@code SettlementPaymentReadModel} 을
 * 대체하기 위한 CQRS read model. PaymentCaptured 이벤트로 적재되며 settlement 가 소유한다.
 * 현재는 opslab 공유 DB 에 있으나(Phase 4 에서 settlement_db 로 이전), 쓰기 주체는 settlement 컨슈머다.
 */
@Entity
@Table(name = "settlement_payment_view")
@Getter
@Setter
@NoArgsConstructor
public class SettlementPaymentViewJpaEntity {

    @Id
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "seller_tier", length = 20)
    private String sellerTier;

    @Column(name = "settlement_cycle", length = 20)
    private String settlementCycle;

    // Phase 3b-4 — QueryDSL/ES 컷오버용 확장 필드
    @Column(name = "payment_method", length = 40)
    private String paymentMethod;

    @Column(name = "refunded_amount", precision = 15, scale = 2)
    private BigDecimal refundedAmount;

    @Column(name = "pg_transaction_id", length = 100)
    private String pgTransactionId;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
