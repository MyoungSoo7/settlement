package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadReputationPort;
import github.lms.lemuel.card.application.port.out.SaveReputationPort;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 셀러 평판 프로젝션 영속 어댑터 — PK(seller_id) 기준 JPA merge 로 멱등 upsert.
 * 프로젝션에 없는 셀러는 {@link ReputationGrade#unknownDefault()}(보수적 기본)로 답한다.
 */
@Component
public class ReputationProjectionPersistenceAdapter implements LoadReputationPort, SaveReputationPort {

    private final SpringDataReputationProjectionRepository repository;

    public ReputationProjectionPersistenceAdapter(SpringDataReputationProjectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public void upsertGrade(String sellerId, ReputationGrade grade) {
        repository.save(new ReputationProjectionJpaEntity(sellerId, grade.name(), Instant.now()));
    }

    @Override
    public ReputationGrade gradeOf(String sellerId) {
        return repository.findById(sellerId)
                .map(e -> ReputationGrade.valueOf(e.getGrade()))
                .orElse(ReputationGrade.unknownDefault());
    }
}
