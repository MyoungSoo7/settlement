package github.lms.lemuel.settlement.adapter.out.readmodel;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Read-only projection of payments table for settlement-service.
 *
 * <p>order-service 가 payments 테이블에 쓰고, settlement-service 는 여기에 정의한 엔티티로
 * 같은 테이블을 읽기 전용으로 본다. 코드 의존성은 모듈 간 끊어진다 (settlement-service 가
 * order-service 의 PaymentJpaEntity 를 직접 import 하지 않음).</p>
 *
 * <p>물리 DB 는 단일 PG 를 공유한다 (포트폴리오 간소화). 운영에서는 Outbox + Kafka 이벤트로
 * settlement-service 자체 테이블에 복제하는 형태로 확장 가능하다.</p>
 */
@Entity
@Immutable
@Table(name = "payments")
@Getter
public class SettlementPaymentReadModel {

    @Id
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "refunded_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundedAmount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "pg_transaction_id", length = 500)
    private String pgTransactionId;

    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
