package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadCollateralDocumentPort;
import github.lms.lemuel.loan.application.port.out.SaveCollateralDocumentPort;
import github.lms.lemuel.loan.domain.CollateralDocument;
import github.lms.lemuel.loan.domain.exception.CollateralDocumentNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 담보서류 영속 어댑터 — collateral_documents.
 */
@Component
public class CollateralDocumentPersistenceAdapter
        implements SaveCollateralDocumentPort, LoadCollateralDocumentPort {

    private final SpringDataCollateralDocumentRepository repository;

    public CollateralDocumentPersistenceAdapter(SpringDataCollateralDocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public CollateralDocument saveNew(CollateralDocument document, byte[] content) {
        return repository.save(CollateralDocumentJpaEntity.fromDomain(document, content)).toDomain();
    }

    @Override
    public CollateralDocument update(CollateralDocument document) {
        CollateralDocumentJpaEntity entity = repository.findById(document.getId())
                .orElseThrow(() -> new CollateralDocumentNotFoundException(document.getId()));
        entity.applyStateFrom(document);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<CollateralDocument> findById(Long id) {
        return repository.findById(id).map(CollateralDocumentJpaEntity::toDomain);
    }

    @Override
    public Optional<CollateralDocument> findByLoanIdAndFileHash(Long securedLoanId, String fileHash) {
        return repository.findBySecuredLoanIdAndFileHash(securedLoanId, fileHash)
                .map(CollateralDocumentJpaEntity::toDomain);
    }

    @Override
    public Optional<CollateralDocument> findLatestByLoanId(Long securedLoanId) {
        return repository.findFirstBySecuredLoanIdOrderByCreatedAtDescIdDesc(securedLoanId)
                .map(CollateralDocumentJpaEntity::toDomain);
    }
}
