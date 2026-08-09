package github.lms.lemuel.account.banking.pension.adapter.out.persistence;

import github.lms.lemuel.account.banking.pension.application.port.out.LoadRetirementPensionPort;
import github.lms.lemuel.account.banking.pension.application.port.out.SaveRetirementPensionPort;
import github.lms.lemuel.account.banking.pension.domain.PensionTransaction;
import github.lms.lemuel.account.banking.pension.domain.PrincipalGuaranteedProduct;
import github.lms.lemuel.account.banking.pension.domain.RetirementPension;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 퇴직연금 영속 어댑터 — 계약 본체는 upsert, 거래는 append-only 로 나눠 다룬다.
 *
 * <p>거래를 통째로 다시 쓰지 않는 이유는 그것이 곧 append-only 위반이기 때문이다. 도메인이 돌려주는
 * 거래 중 <b>식별자가 아직 없는 것</b>만 새 행으로 적재한다 — 복원된 거래는 모두 id 를 갖고 있으므로
 * 이 한 조건으로 신규/기존이 갈린다. 최종 방어선은 {@code UNIQUE(pension_id, seq)} 다.
 */
@Component
public class RetirementPensionPersistenceAdapter
        implements SaveRetirementPensionPort, LoadRetirementPensionPort {

    private final RetirementPensionRepository pensionRepository;
    private final PensionTransactionRepository transactionRepository;

    public RetirementPensionPersistenceAdapter(RetirementPensionRepository pensionRepository,
                                               PensionTransactionRepository transactionRepository) {
        this.pensionRepository = pensionRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    @Transactional
    public RetirementPension save(RetirementPension pension) {
        RetirementPensionJpaEntity saved = pensionRepository.save(toEntity(pension));
        List<PensionTransactionJpaEntity> appended = pension.getTransactions().stream()
                .filter(tx -> tx.getId() == null)
                .map(tx -> toEntity(saved.getId(), tx))
                .toList();
        List<PensionTransactionJpaEntity> stored = new ArrayList<>(
                transactionRepository.findByPensionIdOrderBySeqAsc(saved.getId()));
        if (!appended.isEmpty()) {
            stored.addAll(transactionRepository.saveAll(appended));
        }
        return toDomain(saved, stored);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RetirementPension> findById(Long pensionId) {
        return pensionRepository.findById(pensionId)
                .map(entity -> toDomain(entity, transactionRepository.findByPensionIdOrderBySeqAsc(entity.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetirementPension> findBySubscriberId(String subscriberId) {
        List<RetirementPensionJpaEntity> entities = pensionRepository.findBySubscriberIdOrderByIdAsc(subscriberId);
        if (entities.isEmpty()) {
            return List.of();
        }
        // 계약 수만큼 거래 조회를 반복하지 않도록 한 번에 끌어와 pension_id 로 묶는다(N+1 회피).
        Map<Long, List<PensionTransactionJpaEntity>> byPension = transactionRepository
                .findByPensionIdInOrderBySeqAsc(entities.stream().map(RetirementPensionJpaEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(PensionTransactionJpaEntity::getPensionId));
        return entities.stream()
                .map(entity -> toDomain(entity, byPension.getOrDefault(entity.getId(), List.of())))
                .toList();
    }

    private static RetirementPensionJpaEntity toEntity(RetirementPension pension) {
        PrincipalGuaranteedProduct product = pension.getPrincipalGuaranteedProduct();
        return new RetirementPensionJpaEntity(
                pension.getId(),
                pension.getSubscriberId(),
                pension.getScheme(),
                pension.getEmployerName(),
                pension.getBirthDate(),
                pension.getAnnualRate(),
                product.productName(),
                product.rate(),
                pension.getStatus(),
                pension.getOpenedOn(),
                pension.getLastInterestSettledOn(),
                pension.getBenefitStartedOn(),
                pension.getBenefitType(),
                pension.getAccumulatedAmount(),
                pension.getNextSeq());
    }

    private static PensionTransactionJpaEntity toEntity(Long pensionId, PensionTransaction transaction) {
        return new PensionTransactionJpaEntity(
                null,
                pensionId,
                transaction.getSeq(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getContributionSource(),
                transaction.getMidWithdrawalReason(),
                transaction.getOccurredOn());
    }

    private static RetirementPension toDomain(RetirementPensionJpaEntity entity,
                                              List<PensionTransactionJpaEntity> transactions) {
        return RetirementPension.reconstitute(
                entity.getId(),
                entity.getSubscriberId(),
                entity.getScheme(),
                entity.getEmployerName(),
                entity.getBirthDate(),
                entity.getAnnualRate(),
                new PrincipalGuaranteedProduct(entity.getProductName(), entity.getProductRate()),
                entity.getStatus(),
                entity.getOpenedOn(),
                entity.getLastInterestSettledOn(),
                entity.getBenefitStartedOn(),
                entity.getBenefitType(),
                entity.getAccumulatedAmount(),
                entity.getNextSeq(),
                transactions.stream().map(toDomainTransaction()).toList());
    }

    private static Function<PensionTransactionJpaEntity, PensionTransaction> toDomainTransaction() {
        return tx -> PensionTransaction.reconstitute(
                tx.getId(),
                tx.getSeq(),
                tx.getTxType(),
                tx.getAmount(),
                tx.getContributionSource(),
                tx.getMidWithdrawalReason(),
                tx.getOccurredOn());
    }
}
