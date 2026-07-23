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
 *
 * <p><b>status 는 주문 생성 시점 스냅샷이다.</b> order 는 상태 전이(취소/배송/완료) 이벤트를
 * 발행하지 않으므로 이 필드는 이후 전이를 따라가지 않는다 — 검색 문서(ES orderStatus)의 표시
 * 메타데이터로만 쓰이고, 정산 계산·대사는 이 값을 읽지 않는다(환불 반영은 payment.refunded 가
 * payment_view 로 별도 수행). 주문 상태 실시간 정합이 필요해지면 order 에 상태 전이 이벤트를
 * 신설하고 이 뷰를 갱신하는 컨슈머를 추가하라(ADR 0020 패턴, event-contract-change 절차).
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
