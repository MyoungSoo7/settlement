package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadApplicationDocumentPort;
import github.lms.lemuel.insurance.application.port.out.LoadApplicationSubmissionPort;
import github.lms.lemuel.insurance.application.port.out.SaveApplicationDocumentPort;
import github.lms.lemuel.insurance.domain.ApplicationDocument;
import github.lms.lemuel.insurance.domain.ApplicationDocumentStatus;
import github.lms.lemuel.insurance.domain.exception.ApplicationDocumentNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 청약서류 영속 어댑터 — application_documents (V11).
 */
@Component
public class ApplicationDocumentPersistenceAdapter
        implements SaveApplicationDocumentPort, LoadApplicationDocumentPort, LoadApplicationSubmissionPort {

    private final SpringDataApplicationDocumentRepository repository;

    public ApplicationDocumentPersistenceAdapter(SpringDataApplicationDocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public ApplicationDocument saveNew(ApplicationDocument document, byte[] content) {
        return repository.save(ApplicationDocumentJpaEntity.fromDomain(document, content)).toDomain();
    }

    @Override
    public ApplicationDocument update(ApplicationDocument document) {
        ApplicationDocumentJpaEntity entity = repository.findById(document.getId())
                .orElseThrow(() -> new ApplicationDocumentNotFoundException(document.getId()));
        entity.applyStateFrom(document);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<ApplicationDocument> findById(Long id) {
        return repository.findById(id).map(ApplicationDocumentJpaEntity::toDomain);
    }

    @Override
    public List<ApplicationDocument> findByStatus(ApplicationDocumentStatus status, int limit) {
        return repository.findByStatusOrderByIdDesc(status, PageRequest.of(0, limit)).stream()
                .map(ApplicationDocumentJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<ApplicationDocument> findByApplicationIdAndFileHash(String applicationId, String fileHash) {
        return repository.findByApplicationIdAndFileHash(UUID.fromString(applicationId), fileHash)
                .map(ApplicationDocumentJpaEntity::toDomain);
    }

    @Override
    public Optional<ApplicationDocument> findLatestByApplicationId(String applicationId) {
        return repository.findFirstByApplicationIdOrderByCreatedAtDescIdDesc(UUID.fromString(applicationId))
                .map(ApplicationDocumentJpaEntity::toDomain);
    }

    @Override
    public Optional<Instant> findSubmittedAt(String applicationId) {
        return repository.findApplicationSubmittedAt(UUID.fromString(applicationId));
    }
}
