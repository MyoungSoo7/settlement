package github.lms.lemuel.company.adapter.out.persistence;

import github.lms.lemuel.company.application.port.out.SaveCompanyWorkforcePort;
import github.lms.lemuel.company.domain.CompanyWorkforce;
import github.lms.lemuel.company.domain.WorkplaceRegion;
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
    // sido/sigungu 는 주소에서 파생해 저장한다 — 집계 SQL 이 이 컬럼을 GROUP BY 하므로, 파싱 규칙을
    // SQL 에 재구현하지 않고 도메인 파서(WorkplaceRegion) 한 곳만 정본으로 둘 수 있다.
    private static final String UPSERT_SQL = """
            INSERT INTO company_workforce
                (workplace_name, biz_reg_no_prefix, industry_code, industry_name, address,
                 sido, sigungu, snapshot_month, headcount, monthly_billed_amount, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON CONFLICT (workplace_name, biz_reg_no_prefix, snapshot_month)
            DO UPDATE SET industry_code = EXCLUDED.industry_code,
                          industry_name = EXCLUDED.industry_name,
                          address = EXCLUDED.address,
                          sido = EXCLUDED.sido,
                          sigungu = EXCLUDED.sigungu,
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
                WorkplaceRegion region = workforce.region();
                ps.setString(1, workforce.workplaceName());
                ps.setString(2, workforce.bizRegNoPrefix());
                ps.setString(3, workforce.industryCode());
                ps.setString(4, workforce.industryName());
                ps.setString(5, workforce.address());
                ps.setString(6, region.sido());
                ps.setString(7, region.sigungu());
                ps.setString(8, workforce.snapshotMonth().toString());
                ps.setInt(9, workforce.headcount());
                ps.setBigDecimal(10, workforce.monthlyBilledAmount());
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
        return new UpsertResult(results.length);
    }
}
