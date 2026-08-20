package github.lms.lemuel.education.config;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxEventPersistenceAdapter;
import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.common.outbox.adapter.out.persistence.SpringDataOutboxEventRepository;
import github.lms.lemuel.common.outbox.application.service.TraceContextCapture;
import github.lms.lemuel.education.adapter.out.persistence.CourseJpaEntity;
import github.lms.lemuel.education.adapter.out.persistence.CourseRepository;
import github.lms.lemuel.education.adapter.out.audit.EducationAuditJpaEntity;
import github.lms.lemuel.education.adapter.out.audit.EducationAuditRepository;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan(basePackageClasses = {CourseJpaEntity.class, EducationAuditJpaEntity.class,
        github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxEventJpaEntity.class})
@EnableJpaRepositories(basePackageClasses = {CourseRepository.class, EducationAuditRepository.class, SpringDataOutboxEventRepository.class})
@Import({OutboxEventPersistenceAdapter.class, OutboxSchema.class, TraceContextCapture.class})
public class PersistenceConfig { }
