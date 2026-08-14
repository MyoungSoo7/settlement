package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * statement_payments 테이블 매핑 (V8) — 청구서 납부 멱등 레코드.
 *
 * <p>payment_id 가 UNIQUE 제약으로 보호된다 — 동일 paymentId 재전송은 DB 레벨에서 차단된다.
 */
@Entity
@Table(name = "statement_payments")
public class StatementPaymentJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "statement_id", nullable = false)
    private Long statementId;

    @Column(name = "payment_id", nullable = false, length = 64)
    private String paymentId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_at", nullable = false, insertable = false, updatable = false)
    private Instant paidAt;

    protected StatementPaymentJpaEntity() {
    }

    public static StatementPaymentJpaEntity create(Long statementId, String paymentId,
                                                   BigDecimal amount) {
        StatementPaymentJpaEntity e = new StatementPaymentJpaEntity();
        e.statementId = statementId;
        e.paymentId = paymentId;
        e.amount = amount;
        return e;
    }

    public Long getId() { return id; }
    public Long getStatementId() { return statementId; }
    public String getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
}
