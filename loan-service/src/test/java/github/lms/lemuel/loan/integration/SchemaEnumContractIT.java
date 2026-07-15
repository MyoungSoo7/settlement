package github.lms.lemuel.loan.integration;

import github.lms.lemuel.LoanServiceApplication;
import github.lms.lemuel.loan.domain.CorporateCreditPolicy;
import github.lms.lemuel.loan.domain.LoanLedgerEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도메인 팩토리/정책 ↔ DB CHECK 값 집합의 계약 테스트 — lemuel_loan(opslab), 실 Flyway 체인.
 *
 * <p>loan 원장은 참조유형(ref_type)을 {@link LoanLedgerEntry} 정적 팩토리 5종이 만드는 문자열로,
 * 기업대출 신용등급(credit_grade)을 {@link CorporateCreditPolicy#creditGrade(int)} 가 만드는 A~E 로
 * 적재한다. 두 값 집합은 {@code chk_loan_ledger_ref_type}·{@code chk_corp_loan_credit_grade} CHECK 와
 * 수동 동기화라 드리프트가 런타임 INSERT 실패로만 드러난다 — 이 IT 가 {@code pg_get_constraintdef} 로
 * 실제 CHECK 를 읽어 대조해 그 갭을 빌드 시점으로 앞당긴다(DB 설계 리뷰 R4 후속).
 *
 * <p>기대값은 상수 목록이 아니라 <b>팩토리·정책을 실제로 호출해</b> 수집한다 — 매핑이 바뀌면 CHECK 와
 * 즉시 어긋나 이 테스트가 실패하도록.
 */
@SpringBootTest(
        classes = LoanServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class SchemaEnumContractIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("loan_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    private static final BigDecimal ONE = BigDecimal.ONE;

    @Autowired JdbcTemplate jdbc;

    /** pg_get_constraintdef 로 CHECK 정의를 읽어 IN 목록의 단일 인용 리터럴을 집합으로 뽑는다. */
    private Set<String> checkValues(String constraintName) {
        String def = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, constraintName);
        Matcher m = QUOTED.matcher(def);
        Set<String> values = new LinkedHashSet<>();
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    @Test
    @DisplayName("chk_loan_ledger_ref_type == LoanLedgerEntry 팩토리 5종의 refType (정확 일치)")
    void loanLedgerRefTypeCheckMatchesFactorySetExactly() {
        Set<String> factoryRefTypes = new LinkedHashSet<>(java.util.Arrays.asList(
                LoanLedgerEntry.disbursement(1L, ONE).getRefType(),
                LoanLedgerEntry.feeAccrual(1L, ONE).getRefType(),
                LoanLedgerEntry.repayment(1L, ONE).getRefType(),
                LoanLedgerEntry.corporateDisbursement(1L, ONE).getRefType(),
                LoanLedgerEntry.corporateFeeAccrual(1L, ONE).getRefType()));

        assertThat(checkValues("chk_loan_ledger_ref_type"))
                .containsExactlyInAnyOrderElementsOf(factoryRefTypes);
    }

    @Test
    @DisplayName("chk_corp_loan_credit_grade == CorporateCreditPolicy 가 산출하는 등급집합 A~E (정확 일치)")
    void corpLoanCreditGradeCheckMatchesPolicyGradesExactly() {
        // 등급은 enum 이 아니라 정책의 점수→등급 밴드가 정본이다. 점수 전 구간을 훑어 실제로 나오는
        // 등급 집합을 수집한다(현재 밴드는 A/B/C/D + 미매칭 E → {A,B,C,D,E}).
        CorporateCreditPolicy policy = new CorporateCreditPolicy(new BigDecimal("0.001"), new BigDecimal("0.1"));
        Set<String> grades = new LinkedHashSet<>();
        for (int score = 0; score <= 100; score++) {
            grades.add(policy.creditGrade(score));
        }

        assertThat(checkValues("chk_corp_loan_credit_grade"))
                .containsExactlyInAnyOrderElementsOf(grades);
    }
}
