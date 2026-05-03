package github.lms.lemuel.pgreconciliation.adapter.out.persistence;

import github.lms.lemuel.pgreconciliation.application.port.out.LoadReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.application.port.out.SaveReconciliationRunPort;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * PG 대사 영속성 어댑터.
 *
 * <p>도메인 모델 ↔ JPA 엔티티 변환을 담당. ReconciliationRun 저장 시 자식 Discrepancy 들도
 * 한 번에 저장하고 부모 id 를 자식 runId 에 채워 넣는다.
 */
@Component
public class PgReconciliationPersistenceAdapter
        implements SaveReconciliationRunPort, LoadReconciliationRunPort {

    private final SpringDataPgReconciliationRunRepository runRepository;
    private final SpringDataPgReconciliationDiscrepancyRepository discrepancyRepository;

    public PgReconciliationPersistenceAdapter(
            SpringDataPgReconciliationRunRepository runRepository,
            SpringDataPgReconciliationDiscrepancyRepository discrepancyRepository) {
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
    }

    @Override
    public ReconciliationRun saveAll(ReconciliationRun run) {
        PgReconciliationRunJpaEntity runEntity = toRunEntity(run);
        PgReconciliationRunJpaEntity savedRun = runRepository.save(runEntity);

        List<ReconciliationDiscrepancy> savedDiscrepancies = run.getDiscrepancies().stream()
                .map(d -> toDiscrepancyEntity(d, savedRun.getId()))
                .map(discrepancyRepository::save)
                .map(PgReconciliationPersistenceAdapter::toDiscrepancyDomain)
                .toList();

        return ReconciliationRun.rehydrate(
                savedRun.getId(), savedRun.getPgProvider(), savedRun.getTargetDate(),
                savedRun.getFileName(), savedRun.getStatus(), savedRun.getStartedAt(),
                savedRun.getFinishedAt(), savedRun.getTotalPgRows(), savedRun.getTotalInternalRows(),
                savedRun.getMatchedCount(), savedRun.getDiscrepancyCount(), savedRun.getAutoCorrectedCount(),
                savedRun.getOperatorId(), savedRun.getNote(), savedDiscrepancies
        );
    }

    @Override
    public ReconciliationDiscrepancy save(ReconciliationDiscrepancy discrepancy) {
        PgReconciliationDiscrepancyJpaEntity entity = toDiscrepancyEntity(discrepancy, discrepancy.getRunId());
        PgReconciliationDiscrepancyJpaEntity saved = discrepancyRepository.save(entity);
        return toDiscrepancyDomain(saved);
    }

    @Override
    public Optional<ReconciliationRun> findById(Long id) {
        return runRepository.findById(id).map(runEntity -> {
            List<ReconciliationDiscrepancy> children = discrepancyRepository
                    .findByRunIdOrderByCreatedAtAsc(runEntity.getId())
                    .stream()
                    .map(PgReconciliationPersistenceAdapter::toDiscrepancyDomain)
                    .toList();
            return toRunDomain(runEntity, children);
        });
    }

    @Override
    public List<ReconciliationRun> findRecent(int limit) {
        return runRepository.findRecent(PageRequest.of(0, Math.max(1, limit))).stream()
                .map(e -> toRunDomain(e, List.of()))
                .toList();
    }

    @Override
    public Optional<ReconciliationDiscrepancy> findDiscrepancyById(Long id) {
        return discrepancyRepository.findById(id).map(PgReconciliationPersistenceAdapter::toDiscrepancyDomain);
    }

    private static PgReconciliationRunJpaEntity toRunEntity(ReconciliationRun run) {
        return new PgReconciliationRunJpaEntity(
                run.getId(), run.getPgProvider(), run.getTargetDate(), run.getFileName(),
                run.getStatus(), run.getStartedAt(), run.getFinishedAt(),
                run.getTotalPgRows(), run.getTotalInternalRows(),
                run.getMatchedCount(), run.getDiscrepancyCount(), run.getAutoCorrectedCount(),
                run.getOperatorId(), run.getNote()
        );
    }

    private static PgReconciliationDiscrepancyJpaEntity toDiscrepancyEntity(
            ReconciliationDiscrepancy d, Long runId) {
        return new PgReconciliationDiscrepancyJpaEntity(
                d.getId(), runId, d.getType(),
                d.getPaymentId(), d.getPgTransactionId(),
                d.getInternalAmount(), d.getPgAmount(), d.getDifference(),
                d.getStatus(), d.getResolvedAt(), d.getResolvedBy(), d.getNote(), d.getCreatedAt()
        );
    }

    private static ReconciliationRun toRunDomain(PgReconciliationRunJpaEntity e,
                                                  List<ReconciliationDiscrepancy> children) {
        return ReconciliationRun.rehydrate(
                e.getId(), e.getPgProvider(), e.getTargetDate(), e.getFileName(),
                e.getStatus(), e.getStartedAt(), e.getFinishedAt(),
                e.getTotalPgRows(), e.getTotalInternalRows(),
                e.getMatchedCount(), e.getDiscrepancyCount(), e.getAutoCorrectedCount(),
                e.getOperatorId(), e.getNote(), children
        );
    }

    private static ReconciliationDiscrepancy toDiscrepancyDomain(PgReconciliationDiscrepancyJpaEntity e) {
        return ReconciliationDiscrepancy.rehydrate(
                e.getId(), e.getRunId(), e.getType(),
                e.getPaymentId(), e.getPgTransactionId(),
                e.getInternalAmount(), e.getPgAmount(), e.getDifference(),
                e.getStatus(), e.getResolvedAt(), e.getResolvedBy(), e.getNote(), e.getCreatedAt()
        );
    }
}
