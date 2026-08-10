package github.lms.lemuel.card.adapter.out.persistence;

import github.lms.lemuel.card.application.port.out.LoadDepartmentBudgetPort;
import github.lms.lemuel.card.application.port.out.UpdateDepartmentBudgetPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 부서 예산 영속 어댑터 — {@link LoadDepartmentBudgetPort} + {@link UpdateDepartmentBudgetPort} 구현.
 *
 * <p>{@code incrementApprovedAmount} 는 레코드가 없으면 신규 생성(예산 0, 소진액=delta)한다.
 */
@Component
public class DepartmentBudgetPersistenceAdapter
        implements LoadDepartmentBudgetPort, UpdateDepartmentBudgetPort {

    private final SpringDataDepartmentBudgetRepository repository;

    public DepartmentBudgetPersistenceAdapter(SpringDataDepartmentBudgetRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<DepartmentBudgetRecord> findBudget(Long organizationId, String departmentId,
                                                        int year, int month) {
        return repository.findByOrganizationIdAndDepartmentIdAndBudgetYearAndBudgetMonth(
                        organizationId, departmentId, year, month)
                .map(e -> new DepartmentBudgetRecord(
                        e.getId(), e.getOrganizationId(), e.getDepartmentId(),
                        e.getBudgetYear(), e.getBudgetMonth(),
                        e.getTotalBudget(), e.getApprovedAmount()));
    }

    @Override
    public void incrementApprovedAmount(Long organizationId, String departmentId,
                                         int year, int month, BigDecimal approvedAmount) {
        DepartmentBudgetJpaEntity entity =
                repository.findByOrganizationIdAndDepartmentIdAndBudgetYearAndBudgetMonth(
                                organizationId, departmentId, year, month)
                        .orElseGet(() ->
                                DepartmentBudgetJpaEntity.newEmpty(organizationId, departmentId, year, month));
        entity.incrementApprovedAmount(approvedAmount);
        repository.saveAndFlush(entity);
    }
}
