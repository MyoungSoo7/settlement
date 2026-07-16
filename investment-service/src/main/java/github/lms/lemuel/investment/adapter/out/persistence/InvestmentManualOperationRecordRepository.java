package github.lms.lemuel.investment.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface InvestmentManualOperationRecordRepository
        extends JpaRepository<InvestmentManualOperationRecord, String> {

    /**
     * 멱등 키를 원자적으로 선점한다 — PostgreSQL {@code INSERT ... ON CONFLICT DO NOTHING}.
     *
     * <p>{@code saveAndFlush} 후 {@code DataIntegrityViolationException} 을 잡는 방식은, 같은 트랜잭션
     * (특히 {@code REQUIRES_NEW})에서 유니크 위반이 나면 그 트랜잭션이 rollback-only 로 마킹돼 커밋 시점에
     * {@code UnexpectedRollbackException} 이 터진다(예외를 잡아도 커밋 불가). 이를 피하려고 예외가 아예
     * 발생하지 않는 {@code ON CONFLICT DO NOTHING} 으로 삽입하고, <b>영향 행 수</b>로 승패를 가른다:
     * 1 이면 선점 성공, 0 이면 이미 존재(중복). 동시 삽입도 PK 유니크 인덱스가 정확히 하나만 통과시킨다.
     *
     * <p>테이블은 {@code opslab} 스키마에 산다(Flyway {@code schemas: opslab} + Hibernate
     * {@code default_schema: opslab}). 엔티티 매핑은 무스키마라 Hibernate 가 default_schema 로 해석하지만,
     * 네이티브 SQL 은 커넥션 search_path 를 타므로 여기서 명시 스키마로 한정한다(Boot4 네이티브 @Query 는
     * 구조적 SpEL 미평가 → Outbox 와 동일하게 스키마를 하드코딩; default_schema 설정과 일치).
     *
     * @return 삽입된 행 수 (신규 선점=1, 중복=0)
     */
    @Modifying
    @Query(value = "INSERT INTO opslab.investment_manual_operation_idempotency "
            + "(idempotency_key, endpoint, operator, created_at) "
            + "VALUES (:key, :endpoint, :operator, :createdAt) "
            + "ON CONFLICT (idempotency_key) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("key") String key,
                       @Param("endpoint") String endpoint,
                       @Param("operator") String operator,
                       @Param("createdAt") LocalDateTime createdAt);
}
