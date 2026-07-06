package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "companies")
public class CompanyJpaEntity {

    @Id
    @Column(name = "stock_code", length = 6)
    private String stockCode;

    @Column(name = "corp_code", length = 8, unique = true)
    private String corpCode;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "market", nullable = false, length = 10)
    private String market;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CompanyJpaEntity() {
    }

    Company toDomain() {
        return new Company(stockCode, corpCode, name, market);
    }
}
