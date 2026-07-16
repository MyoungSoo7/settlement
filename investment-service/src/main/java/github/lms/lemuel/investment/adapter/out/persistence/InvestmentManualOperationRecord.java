package github.lms.lemuel.investment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * 투자 수동 REST 조작의 멱등 키 선점 레코드 (테이블 {@code investment_manual_operation_idempotency}).
 *
 * <p>선점(write)은 {@link InvestmentManualOperationRecordRepository#insertIfAbsent} 의
 * {@code INSERT ... ON CONFLICT DO NOTHING} 로 원자 수행하므로, 이 엔티티는 주로 조회(findById/existsById)
 * 용도로 쓰인다. {@link Persistable#isNew()} 를 항상 {@code true} 로 둬, 혹시 {@code save()} 경로를 타더라도
 * merge 가 아닌 {@code persist} 가 호출되도록 보존한다(중복 키는 상위 upsert 가 이미 걸러낸다).
 */
@Entity
@Table(name = "investment_manual_operation_idempotency")
public class InvestmentManualOperationRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "endpoint", nullable = false, length = 200)
    private String endpoint;

    @Column(name = "operator", length = 200)
    private String operator;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected InvestmentManualOperationRecord() {
    }

    public InvestmentManualOperationRecord(String idempotencyKey, String endpoint, String operator,
                                           LocalDateTime createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.operator = operator;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return true;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getOperator() {
        return operator;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
