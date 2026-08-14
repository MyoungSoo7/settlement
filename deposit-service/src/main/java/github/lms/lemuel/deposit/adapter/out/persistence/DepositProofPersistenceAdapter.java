package github.lms.lemuel.deposit.adapter.out.persistence;

import github.lms.lemuel.deposit.application.port.out.LoadDepositProofPort;
import github.lms.lemuel.deposit.application.port.out.SaveDepositProofPort;
import github.lms.lemuel.deposit.domain.DepositProof;
import github.lms.lemuel.deposit.domain.exception.DepositProofNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 예치금 증빙 영속 어댑터 — deposit_proofs.
 */
@Component
public class DepositProofPersistenceAdapter implements SaveDepositProofPort, LoadDepositProofPort {

    private final SpringDataDepositProofRepository repository;

    public DepositProofPersistenceAdapter(SpringDataDepositProofRepository repository) {
        this.repository = repository;
    }

    @Override
    public DepositProof saveNew(DepositProof proof, byte[] content) {
        return repository.save(DepositProofJpaEntity.fromDomain(proof, content)).toDomain();
    }

    @Override
    public DepositProof update(DepositProof proof) {
        DepositProofJpaEntity entity = repository.findById(proof.getId())
                .orElseThrow(() -> new DepositProofNotFoundException(proof.getId()));
        entity.applyStateFrom(proof);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<DepositProof> findById(Long id) {
        return repository.findById(id).map(DepositProofJpaEntity::toDomain);
    }

    @Override
    public Optional<DepositProof> findByReferenceAndFileHash(Long sellerId, String referenceType,
                                                             String referenceId, String fileHash) {
        return repository.findBySellerIdAndReferenceTypeAndReferenceIdAndFileHash(
                        sellerId, referenceType, referenceId, fileHash)
                .map(DepositProofJpaEntity::toDomain);
    }

    @Override
    public Optional<DepositProof> findLatestByReference(Long sellerId, String referenceType,
                                                        String referenceId) {
        return repository.findFirstBySellerIdAndReferenceTypeAndReferenceIdOrderByCreatedAtDescIdDesc(
                        sellerId, referenceType, referenceId)
                .map(DepositProofJpaEntity::toDomain);
    }
}
