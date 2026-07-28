package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.domain.CompanyWorkforce;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

@Entity
@Table(name = "company_workforce")
public class CompanyWorkforceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workplace_name", nullable = false, length = 200)
    private String workplaceName;

    @Column(name = "biz_reg_no_prefix", length = 6)
    private String bizRegNoPrefix;

    @Column(name = "industry_name", length = 100)
    private String industryName;

    @Column(name = "address", length = 300)
    private String address;

    @Column(name = "snapshot_month", nullable = false, length = 7)
    private String snapshotMonth;

    @Column(name = "headcount", nullable = false)
    private int headcount;

    @Column(name = "monthly_billed_amount", nullable = false, precision = 15, scale = 0)
    private BigDecimal monthlyBilledAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompanyWorkforceJpaEntity() {
    }

    CompanyWorkforce toDomain() {
        return new CompanyWorkforce(workplaceName, bizRegNoPrefix, industryName, address,
                YearMonth.parse(snapshotMonth), headcount, monthlyBilledAmount);
    }
}
