package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.LedgerStatus;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.settlement.domain.SettlementStatus;
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

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도메인 enum ↔ DB CHECK 값 집합의 계약 테스트 — settlement_db(public), 실 Flyway 체인.
 *
 * <p>enum 과 {@code chk_*} CHECK 의 허용값은 수동 동기화라 드리프트가 런타임 INSERT 실패로만 드러난다.
 * 이 IT 는 {@code pg_get_constraintdef} 로 실제 CHECK 정의를 읽어 도메인 enum 과 대조해 그 갭을 빌드
 * 시점으로 앞당긴다(DB 설계 리뷰 R4 후속). 파싱은 IN 목록의 단일 인용 리터럴만 뽑는 단순 정규식이다.
 *
 * <p>대조 규칙은 두 가지다:
 * <ul>
 *   <li><b>정확 일치</b>(ledger status/entry_type/account/ref_type): CHECK == enum.values().</li>
 *   <li><b>상위집합</b>(settlements.status): 코어 상태머신 enum ⊆ CHECK. status CHECK 는 승인 워크플로·
 *       레거시 읽기 경로까지 포함하는 3계층 합집합이라(V20260715110100 주석), 신규 기록이 쓰는 코어 5종이
 *       모두 허용되는지(=enum 이 CHECK 의 부분집합인지)만 검증한다.</li>
 * </ul>
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
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
    static final PostgreSQLContainer<?> SETTLEMENT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", SETTLEMENT_DB::getJdbcUrl);
        r.add("spring.datasource.username", SETTLEMENT_DB::getUsername);
        r.add("spring.datasource.password", SETTLEMENT_DB::getPassword);
    }

    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

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

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("chk_settlements_status ⊇ SettlementStatus (코어 5종은 CHECK 의 부분집합, 3계층 상위집합)")
    void settlementsStatusIsSupersetOfCoreEnum() {
        Set<String> check = checkValues("chk_settlements_status");

        assertThat(check).containsAll(names(SettlementStatus.values()));
        // 상위집합 근거: 승인 워크플로(②) + 레거시 읽기 경로(③) 값도 허용돼야 실존 조회가 깨지지 않는다.
        assertThat(check).contains("WAITING_APPROVAL", "APPROVED", "REJECTED", "PENDING", "CONFIRMED");
    }

    @Test
    @DisplayName("chk_ledger_status == LedgerStatus (정확 일치)")
    void ledgerStatusMatchesEnumExactly() {
        assertThat(checkValues("chk_ledger_status"))
                .containsExactlyInAnyOrderElementsOf(names(LedgerStatus.values()));
    }

    @Test
    @DisplayName("chk_ledger_entry_type == LedgerEntryType (정확 일치)")
    void ledgerEntryTypeMatchesEnumExactly() {
        assertThat(checkValues("chk_ledger_entry_type"))
                .containsExactlyInAnyOrderElementsOf(names(LedgerEntryType.values()));
    }

    @Test
    @DisplayName("chk_ledger_debit_account / chk_ledger_credit_account == AccountType (정확 일치)")
    void ledgerAccountChecksMatchAccountTypeExactly() {
        Set<String> accounts = names(AccountType.values());
        assertThat(checkValues("chk_ledger_debit_account")).containsExactlyInAnyOrderElementsOf(accounts);
        assertThat(checkValues("chk_ledger_credit_account")).containsExactlyInAnyOrderElementsOf(accounts);
    }

    @Test
    @DisplayName("chk_ledger_ref_type == ReferenceType (정확 일치)")
    void ledgerRefTypeMatchesEnumExactly() {
        assertThat(checkValues("chk_ledger_ref_type"))
                .containsExactlyInAnyOrderElementsOf(names(ReferenceType.values()));
    }
}
