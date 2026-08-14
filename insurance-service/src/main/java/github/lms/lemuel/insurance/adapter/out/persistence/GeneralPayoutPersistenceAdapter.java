package github.lms.lemuel.insurance.adapter.out.persistence;

import github.lms.lemuel.insurance.application.port.out.LoadGeneralPayoutPort;
import github.lms.lemuel.insurance.application.port.out.SaveGeneralPayoutPort;
import github.lms.lemuel.insurance.domain.GeneralPayout;
import github.lms.lemuel.insurance.domain.GeneralPayoutStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class GeneralPayoutPersistenceAdapter implements LoadGeneralPayoutPort, SaveGeneralPayoutPort {

    private final SpringDataGeneralPayoutRepository repository;

    public GeneralPayoutPersistenceAdapter(SpringDataGeneralPayoutRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<GeneralPayout> findRequested() {
        return repository.findByStatusOrderByIdAsc(GeneralPayoutStatus.REQUESTED)
                .stream().map(GeneralPayoutJpaEntity::toDomain).toList();
    }

    @Override
    public List<GeneralPayout> findByPolicyId(String policyId) {
        return repository.findByPolicyIdOrderByIdAsc(UUID.fromString(policyId))
                .stream().map(GeneralPayoutJpaEntity::toDomain).toList();
    }

    @Override
    public GeneralPayout insert(GeneralPayout payout) {
        // 이중 INSERT 는 uq_general_payout_policy_type 이 최후 방어한다 (멱등 L3)
        return repository.saveAndFlush(GeneralPayoutJpaEntity.fromRequested(payout)).toDomain();
    }

    @Override
    public GeneralPayout save(GeneralPayout payout) {
        // 배치 전이는 항상 기존 행 대상 — 신규 생성은 insert 경로만 쓴다.
        GeneralPayoutJpaEntity entity = repository.findById(payout.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "존재하지 않는 일반지급 행에 전이 결과를 반영할 수 없습니다: id=" + payout.getId()));
        entity.applyTransitionResult(payout);
        return repository.saveAndFlush(entity).toDomain();
    }
}
