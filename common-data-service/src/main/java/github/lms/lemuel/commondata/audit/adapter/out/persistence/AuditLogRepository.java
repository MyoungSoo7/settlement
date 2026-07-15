package github.lms.lemuel.commondata.audit.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** audit_logs INSERT 전용 Spring Data 리포지토리. 단건 조회는 하지 않는다(감사행은 append-only). */
public interface AuditLogRepository extends JpaRepository<AuditLogJpaEntity, Long> {
}
