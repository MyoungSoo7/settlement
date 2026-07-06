package github.lms.lemuel.loan.adapter.out.persistence;

import github.lms.lemuel.loan.application.port.out.LoadSellerReputationPort;
import github.lms.lemuel.loan.application.port.out.SaveSellerReputationPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class SellerReputationPersistenceAdapter implements SaveSellerReputationPort, LoadSellerReputationPort {

    private final SellerReputationRepository repository;

    public SellerReputationPersistenceAdapter(SellerReputationRepository repository) {
        this.repository = repository;
    }

    @Override
    public void upsert(Long sellerId, String stockCode, int score, String grade) {
        // sellerId 가 할당 식별자이므로 save() 는 merge = 멱등 UPSERT.
        repository.save(new SellerReputationJpaEntity(sellerId, stockCode, score, grade, LocalDateTime.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findGrade(Long sellerId) {
        return repository.findById(sellerId).map(SellerReputationJpaEntity::getGrade);
    }
}
