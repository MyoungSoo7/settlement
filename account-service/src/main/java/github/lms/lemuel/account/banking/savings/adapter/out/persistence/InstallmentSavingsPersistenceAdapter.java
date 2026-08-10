package github.lms.lemuel.account.banking.savings.adapter.out.persistence;

import github.lms.lemuel.account.banking.savings.application.port.out.LoadInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.application.port.out.SaveInstallmentSavingsPort;
import github.lms.lemuel.account.banking.savings.domain.InstallmentSavings;
import github.lms.lemuel.account.banking.savings.domain.SavingsInstallment;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 적금 영속 어댑터 — 계약과 회차 두 테이블을 하나의 애그리거트로 접합한다.
 *
 * <p>회차는 <b>append-only</b> 로 다룬다. 이미 저장된 회차를 다시 쓰지 않으므로 (1) 낙관적 락 없이도
 * 기존 회차가 덮어써지지 않고, (2) 동시에 같은 회차를 납입하려는 두 요청은
 * {@code uq_savings_installment_round} 에서 하나가 반드시 실패한다.
 */
@Component
public class InstallmentSavingsPersistenceAdapter
        implements LoadInstallmentSavingsPort, SaveInstallmentSavingsPort {

    private final InstallmentSavingsRepository savingsRepository;
    private final SavingsInstallmentRepository installmentRepository;

    public InstallmentSavingsPersistenceAdapter(InstallmentSavingsRepository savingsRepository,
                                                SavingsInstallmentRepository installmentRepository) {
        this.savingsRepository = savingsRepository;
        this.installmentRepository = installmentRepository;
    }

    @Override
    public Optional<InstallmentSavings> findById(Long savingsId) {
        return savingsRepository.findById(savingsId)
                .map(entity -> toDomain(entity,
                        installmentRepository.findBySavingsIdOrderByRoundNoAsc(entity.getId())));
    }

    @Override
    public List<InstallmentSavings> findByDepositorId(String depositorId) {
        List<InstallmentSavingsJpaEntity> entities =
                savingsRepository.findByDepositorIdOrderByOpenedOnDescIdDesc(depositorId);
        if (entities.isEmpty()) {
            return List.of();
        }
        // 계약별로 회차를 따로 읽으면 목록 길이만큼 쿼리가 늘어난다(N+1) — IN 한 방으로 읽고 나눈다.
        Map<Long, List<SavingsInstallmentJpaEntity>> bySavingsId = installmentRepository
                .findBySavingsIdInOrderByRoundNoAsc(entities.stream()
                        .map(InstallmentSavingsJpaEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(SavingsInstallmentJpaEntity::getSavingsId));
        return entities.stream()
                .map(entity -> toDomain(entity, bySavingsId.getOrDefault(entity.getId(), List.of())))
                .toList();
    }

    @Override
    public InstallmentSavings save(InstallmentSavings savings) {
        InstallmentSavingsJpaEntity saved =
                savingsRepository.saveAndFlush(InstallmentSavingsJpaEntity.fromDomain(savings));
        Long savingsId = saved.getId();

        Set<Integer> alreadyPersisted = new HashSet<>();
        installmentRepository.findBySavingsIdOrderByRoundNoAsc(savingsId)
                .forEach(row -> alreadyPersisted.add(row.getRoundNo()));

        List<SavingsInstallmentJpaEntity> newRows = savings.getInstallments().stream()
                .filter(installment -> !alreadyPersisted.contains(installment.getRound()))
                .map(installment -> SavingsInstallmentJpaEntity.fromDomain(savingsId, installment))
                .toList();
        if (!newRows.isEmpty()) {
            installmentRepository.saveAll(newRows);
        }
        return toDomain(saved, installmentRepository.findBySavingsIdOrderByRoundNoAsc(savingsId));
    }

    // ── 매퍼 ─────────────────────────────────────────────────────────────────

    private static InstallmentSavings toDomain(InstallmentSavingsJpaEntity entity,
                                               List<SavingsInstallmentJpaEntity> installmentRows) {
        List<SavingsInstallment> installments = installmentRows.stream()
                .map(row -> SavingsInstallment.reconstitute(
                        row.getId(), row.getRoundNo(), row.getAmount(),
                        row.getDueDate(), row.getPaidOn(), row.getOverdueDays()))
                .toList();
        return InstallmentSavings.reconstitute(
                entity.getId(),
                entity.getDepositorId(),
                entity.getProductName(),
                entity.getSavingsType(),
                entity.getMonthlyAmount(),
                entity.getPaymentLimit(),
                entity.getAnnualRate(),
                entity.getEarlyTerminationRate(),
                entity.getTermMonths(),
                entity.getOpenedOn(),
                entity.getMaturityDate(),
                entity.getStatus(),
                entity.getClosedOn(),
                entity.getSettledInterest(),
                entity.getPayoutAmount(),
                installments);
    }
}
