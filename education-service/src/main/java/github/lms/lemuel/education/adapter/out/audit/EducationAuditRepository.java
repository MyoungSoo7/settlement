package github.lms.lemuel.education.adapter.out.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EducationAuditRepository extends JpaRepository<EducationAuditJpaEntity, Long> { }
