package github.lms.lemuel.settlement.adapter.out.persistence;

import github.lms.lemuel.settlement.application.port.out.LoadCommissionRatePolicyPort;
import github.lms.lemuel.settlement.domain.CommissionRatePolicy;
import github.lms.lemuel.settlement.domain.RateScope;
import github.lms.lemuel.settlement.domain.SellerTier;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 요율 정책 조회 어댑터 (ADR 0032).
 *
 * <p>셀러·등급 후보만 좁혀 오고 우선순위 판정은 도메인({@code CommissionRatePolicy.resolve})이 한다 —
 * 미리보기와 실제 적용이 같은 판정을 쓰게 하기 위함이다.
 */
@Repository
public class CommissionRatePolicyPersistenceAdapter implements LoadCommissionRatePolicyPort,
        github.lms.lemuel.settlement.application.port.out.SaveCommissionRatePolicyPort,
        github.lms.lemuel.settlement.application.port.out.ListCommissionRatePoliciesPort {

    private final SpringDataCommissionRatePolicyRepository repository;

    public CommissionRatePolicyPersistenceAdapter(SpringDataCommissionRatePolicyRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<PolicyRow> findRows(boolean includeClosed) {
        return (includeClosed ? repository.findAllOrdered() : repository.findOpenOrdered(LocalDate.now())).stream()
                .map(CommissionRatePolicyPersistenceAdapter::toRow)
                .toList();
    }

    private static PolicyRow toRow(CommissionRatePolicyJpaEntity e) {
        return new PolicyRow(e.getId(), RateScope.valueOf(e.getScope()), e.getScopeKey(), e.getRate(),
                e.getEffectiveFrom(), e.getEffectiveTo(), e.getReason(), e.getCreatedBy(),
                e.getCreatedAt(), e.getClosedAt());
    }

    @Override
    public List<CommissionRatePolicy> findEffectiveCandidates(Long sellerId, SellerTier tier, LocalDate at) {
        if (at == null) {
            return List.of();
        }
        String sellerKey = sellerId == null ? null : String.valueOf(sellerId);
        String tierKey = tier == null ? SellerTier.NORMAL.name() : tier.name();
        return repository.findCandidates(at, sellerKey, tierKey).stream()
                .map(CommissionRatePolicyPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public CommissionRatePolicy save(
            github.lms.lemuel.settlement.application.port.in.RegisterCommissionRatePolicyUseCase
                    .RegisterPolicyCommand command) {
        CommissionRatePolicyJpaEntity e = new CommissionRatePolicyJpaEntity();
        e.setScope(command.scope().name());
        e.setScopeKey(command.scopeKey());
        e.setRate(command.rate());
        e.setEffectiveFrom(command.effectiveFrom());
        e.setEffectiveTo(command.effectiveTo());
        e.setReason(command.reason());
        e.setCreatedBy(command.createdBy());
        e.setCreatedAt(java.time.OffsetDateTime.now());
        return toDomain(repository.save(e));
    }

    /** 조기 종료 — 행 UPDATE 로 요율을 바꾸는 것이 아니라 유효기간만 닫는다(이력 보존). */
    @Override
    public void close(Long policyId) {
        repository.findById(policyId).ifPresent(e -> {
            e.setClosedAt(java.time.OffsetDateTime.now());
            repository.save(e);
        });
    }

    static CommissionRatePolicy toDomain(CommissionRatePolicyJpaEntity e) {
        return CommissionRatePolicy.rehydrate(e.getId(), RateScope.valueOf(e.getScope()),
                e.getScopeKey(), e.getRate(), e.getEffectiveFrom(), e.getEffectiveTo());
    }
}
