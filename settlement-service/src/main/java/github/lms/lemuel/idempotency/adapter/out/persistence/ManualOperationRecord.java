package github.lms.lemuel.idempotency.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * 운영자 수동 REST 조작의 멱등 키 선점 레코드 (테이블 {@code manual_operation_idempotency}).
 *
 * <p>선점은 예외 기반이 아니라 {@link ManualOperationRecordRepository#insertIfAbsent} 의
 * {@code INSERT ... ON CONFLICT DO NOTHING} 원자 upsert(영향 행 수)로 판정한다 — 유니크 위반 예외를
 * 잡는 방식은 REQUIRES_NEW 트랜잭션을 rollback-only 로 물들여 커밋 시점 500 을 유발한다.
 * {@link Persistable#isNew()} 는 항상 {@code true} — 이 레코드는 갱신되지 않는 append-only 선점 표식이다.
 */
@Entity
@Table(name = "manual_operation_idempotency")
public class ManualOperationRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "endpoint", nullable = false, length = 200)
    private String endpoint;

    @Column(name = "operator", length = 200)
    private String operator;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ManualOperationRecord() {
    }

    public ManualOperationRecord(String idempotencyKey, String endpoint, String operator, Instant createdAt) {
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
