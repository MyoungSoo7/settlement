package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.SaveCompanyWorkforcePort;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * CSV 임포트 전용 벌크 upsert. JPA per-entity persist 는 수십만 건 규모에 부적합해
 * {@link JdbcTemplate} batchUpdate 를 직접 쓴다(company-service 는 이미
 * {@code PartitionMaintenanceRunner} 에서 JdbcTemplate 을 쓰고 있어 컨벤션 일치).
 */
@Component
public class CompanyWorkforceBulkPersistenceAdapter implements SaveCompanyWorkforcePort {

    // 같은 (사업장명, 사업자등록번호앞6자리, 자료생성년월) 재적재는 갱신 — 월별 재수집 멱등.
    private static final String UPSERT_SQL = """
            INSERT INTO company_workforce
                (workplace_name, biz_reg_no_prefix, industry_name, address, snapshot_month, headcount, monthly_billed_amount, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (workplace_name, biz_reg_no_prefix, snapshot_month)
            DO UPDATE SET industry_name = EXCLUDED.industry_name,
                          address = EXCLUDED.address,
                          headcount = EXCLUDED.headcount,
                          monthly_billed_amount = EXCLUDED.monthly_billed_amount
            """;

    private final JdbcTemplate jdbcTemplate;

    public CompanyWorkforceBulkPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UpsertResult batchUpsert(List<CompanyWorkforce> batch) {
        int[] results = jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CompanyWorkforce workforce = batch.get(i);
                ps.setString(1, workforce.workplaceName());
                ps.setString(2, workforce.bizRegNoPrefix());
                ps.setString(3, workforce.industryName());
                ps.setString(4, workforce.address());
                ps.setString(5, workforce.snapshotMonth().toString());
                ps.setInt(6, workforce.headcount());
                ps.setBigDecimal(7, workforce.monthlyBilledAmount());
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
        return new UpsertResult(results.length);
    }
}
