package github.lms.lemuel.card.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * department_budgets Spring Data 레포지터리.
 */
public interface SpringDataDepartmentBudgetRepository
        extends JpaRepository<DepartmentBudgetJpaEntity, Long> {

    Optional<DepartmentBudgetJpaEntity> findByOrganizationIdAndDepartmentIdAndBudgetYearAndBudgetMonth(
            Long organizationId, String departmentId, int budgetYear, int budgetMonth);
}
