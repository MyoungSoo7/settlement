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
 * settlement 소유 주문 프로젝션 (ADR 0020 Phase 3b).
 * order orders 를 @Immutable 로 직접 매핑하던 {@code SettlementOrderReadModel} 을 대체한다.
 * OrderCreated 이벤트로 적재되며 settlement 가 소유한다.
 */
@Entity
@Table(name = "settlement_order_view")
@Getter
@Setter
@NoArgsConstructor
public class SettlementOrderViewJpaEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id")
    private Long productId;

    @Column(length = 40)
    private String status;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
