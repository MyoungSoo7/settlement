package github.lms.lemuel.idempotency.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ManualOperationRecordRepository extends JpaRepository<ManualOperationRecord, String> {

    /**
     * 멱등 키를 원자적으로 선점한다 — PostgreSQL {@code INSERT ... ON CONFLICT DO NOTHING}.
     *
     * <p>{@code saveAndFlush} 후 {@code DataIntegrityViolationException} 을 잡는 방식은, 같은 트랜잭션
     * (특히 {@code REQUIRES_NEW})에서 유니크 위반이 나면 그 트랜잭션이 rollback-only 로 마킹돼 커밋 시점에
     * {@code UnexpectedRollbackException} 이 터진다(예외를 잡아도 커밋 불가 → 중복 요청이 409 가 아니라 500).
     * 예외가 아예 발생하지 않는 upsert 로 삽입하고 <b>영향 행 수</b>로 승패를 가른다: 1=선점 성공, 0=중복.
     * 동시 삽입도 PK 유니크 인덱스가 정확히 하나만 통과시킨다. (investment 의
     * {@code InvestmentManualOperationRecordRepository#insertIfAbsent} 와 동형 — 실DB IT 로 검증된 패턴)
     *
     * @return 삽입된 행 수 (신규 선점=1, 중복=0)
     */
    @Modifying
    @Query(value = "INSERT INTO manual_operation_idempotency "
            + "(idempotency_key, endpoint, operator, created_at) "
            + "VALUES (:key, :endpoint, :operator, :createdAt) "
            + "ON CONFLICT (idempotency_key) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("key") String key,
                       @Param("endpoint") String endpoint,
                       @Param("operator") String operator,
                       @Param("createdAt") Instant createdAt);
}
