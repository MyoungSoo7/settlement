package github.lms.lemuel.card.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * department_budgets 테이블 매핑 (V9).
 *
 * <p>(organization_id, department_id, budget_year, budget_month) 조합이 UNIQUE 자연키.
 */
@Entity
@Table(name = "department_budgets")
public class DepartmentBudgetJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "department_id", nullable = false, length = 64)
    private String departmentId;

    @Column(name = "budget_year", nullable = false)
    private int budgetYear;

    @Column(name = "budget_month", nullable = false)
    private int budgetMonth;

    @Column(name = "total_budget", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalBudget;

    @Column(name = "approved_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected DepartmentBudgetJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // ── getters ──

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public String getDepartmentId() { return departmentId; }
    public int getBudgetYear() { return budgetYear; }
    public int getBudgetMonth() { return budgetMonth; }
    public BigDecimal getTotalBudget() { return totalBudget; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }

    public void incrementApprovedAmount(BigDecimal delta) {
        this.approvedAmount = this.approvedAmount.add(delta);
    }

    public static DepartmentBudgetJpaEntity newEmpty(Long organizationId, String departmentId,
                                                      int year, int month) {
        DepartmentBudgetJpaEntity e = new DepartmentBudgetJpaEntity();
        e.organizationId = organizationId;
        e.departmentId = departmentId;
        e.budgetYear = year;
        e.budgetMonth = month;
        e.totalBudget = BigDecimal.ZERO;
        e.approvedAmount = BigDecimal.ZERO;
        e.updatedAt = Instant.now();
        return e;
    }
}
