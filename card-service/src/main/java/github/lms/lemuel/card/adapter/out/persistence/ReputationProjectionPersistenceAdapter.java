package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadReputationPort;
import github.lms.lemuel.card.application.port.out.SaveReputationPort;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.springframework.stereotype.Component;

@Component
public class ReputationProjectionPersistenceAdapter implements LoadReputationPort, SaveReputationPort {

    private final SpringDataReputationProjectionRepository repository;

    public ReputationProjectionPersistenceAdapter(SpringDataReputationProjectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReputationGrade gradeOf(String sellerId) {
        return repository.findById(sellerId)
                .map(e -> ReputationGrade.from(e.getGrade()))
                .orElseGet(ReputationGrade::unknownDefault);
    }

    @Override
    public void upsertGrade(String sellerId, String grade) {
        ReputationProjectionJpaEntity entity = repository.findById(sellerId).orElse(null);
        if (entity == null) {
            repository.save(new ReputationProjectionJpaEntity(sellerId, grade));
            return;
        }
        entity.setGrade(grade);
        repository.save(entity);
    }
}
