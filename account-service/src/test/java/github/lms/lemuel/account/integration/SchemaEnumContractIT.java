package github.lms.lemuel.account.integration;

import github.lms.lemuel.AccountServiceApplication;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도메인 enum/팩토리 ↔ DB CHECK 값 집합의 계약 테스트 — lemuel_account(opslab), 실 Flyway 체인.
 *
 * <p>계정계 GL 은 debit/credit 계정을 {@link GlAccount} enum 으로, 분개 참조유형(ref_type)을
 * {@link AccountEntry} 정적 팩토리 6종이 만드는 문자열로 적재한다. 두 값 집합은 {@code chk_account_entry_*}
 * CHECK 와 수동 동기화라 드리프트가 런타임 INSERT 실패로만 드러난다. 이 IT 가 {@code pg_get_constraintdef}
 * 로 실제 CHECK 를 읽어 대조해 그 갭을 빌드 시점으로 앞당긴다(DB 설계 리뷰 R4 후속).
 *
 * <p>ref_type 기대값은 enum 이 아니라 <b>팩토리를 실제로 호출해</b> 수집한다 — 팩토리 매핑이 바뀌면
 * (신규 이벤트 소비 추가 등) CHECK 와 즉시 어긋나 이 테스트가 실패하도록.
 */
@SpringBootTest(
        classes = AccountServiceApplication.class,
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
    static final PostgreSQLContainer<?> ACCOUNT_DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lemuel_account").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", ACCOUNT_DB::getJdbcUrl);
        r.add("spring.datasource.username", ACCOUNT_DB::getUsername);
        r.add("spring.datasource.password", ACCOUNT_DB::getPassword);
        r.add("POSTGRES_USER", ACCOUNT_DB::getUsername);
        r.add("POSTGRES_PASSWORD", ACCOUNT_DB::getPassword);
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

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("chk_account_entry_debit_account / credit_account == GlAccount (정확 일치)")
    void accountChecksMatchGlAccountExactly() {
        Set<String> accounts = names(GlAccount.values());
        assertThat(checkValues("chk_account_entry_debit_account")).containsExactlyInAnyOrderElementsOf(accounts);
        assertThat(checkValues("chk_account_entry_credit_account")).containsExactlyInAnyOrderElementsOf(accounts);
    }

    @Test
    @DisplayName("chk_account_entry_ref_type == AccountEntry 팩토리 17종의 refType (정확 일치, ADR 0026 Option ① + ADR 0027 §B)")
    void refTypeCheckMatchesFactorySetExactly() {
        Set<String> factoryRefTypes = new LinkedHashSet<>(Arrays.asList(
                AccountEntry.settlementCreatedImmediate("s", "1", ONE).getRefType(),
                AccountEntry.settlementHoldbackRecognized("s", "1", ONE).getRefType(),
                AccountEntry.settlementConfirmed("s", "1", ONE).getRefType(),
                AccountEntry.payoutCompleted("s", "1", ONE).getRefType(),
                AccountEntry.settlementScheduledClearing("s", ONE).getRefType(),
                AccountEntry.holdbackReleased("s", "1", ONE).getRefType(),
                AccountEntry.holdbackConsumed("s", "1", ONE).getRefType(),
                AccountEntry.settlementAdjusted("s", "1", ONE, GlAccount.SELLER_PAYABLE).getRefType(),
                AccountEntry.settlementCanceledPayable("s", "1", ONE).getRefType(),
                AccountEntry.settlementCanceledHoldback("s", "1", ONE).getRefType(),
                AccountEntry.recoveryOpened("s", "1", ONE).getRefType(),
                AccountEntry.recoveryOffset("s", "1", ONE).getRefType(),
                AccountEntry.loanDisbursed("s", "1", ONE).getRefType(),
                AccountEntry.loanRepaid("s", "1", ONE).getRefType(),
                AccountEntry.corporateLoanDisbursed("005930", "1", ONE).getRefType(),
                AccountEntry.investmentExecuted("s", "1", ONE).getRefType(),
                AccountEntry.withholdingAccrued("s", "1", ONE).getRefType()));

        assertThat(checkValues("chk_account_entry_ref_type"))
                .containsExactlyInAnyOrderElementsOf(factoryRefTypes);
    }
}
