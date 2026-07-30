package github.lms.lemuel.account.integration;

import github.lms.lemuel.AccountServiceApplication;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0030 Phase 1 — 실체화 잔액(account_balances)이 원장 재합산과 일치하는지 실 PG 로 증명한다.
 *
 * <p>파생 캐시의 유일한 존재 이유는 "원장을 다시 더한 값과 같다"는 것이다. 그 등식이 깨지면 payout 의
 * 음수 방지 판단이 잘못된 잔액 위에서 이뤄지므로, 다음 두 축을 고정한다:
 * <ul>
 *   <li><b>정합</b> — 여러 전표 적재 후 실체화 잔액 == 원장 재합산(Σcredit − Σdebit)</li>
 *   <li><b>멱등</b> — 같은 자연키 재수신은 전표도 잔액도 늘리지 않는다(중복 기표가 잔액만 부풀리는 회귀 차단)</li>
 * </ul>
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
class MaterializedBalanceIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
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

    @Autowired RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Autowired LoadAccountEntryPort loadAccountEntryPort;
    @Autowired JdbcTemplate jdbc;

    /** 원장을 직접 재합산한 값 — 실체화 잔액의 정답지(비교 기준). */
    private BigDecimal ledgerRecomputed(String ownerId, GlAccount account) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN credit_account = ? THEN amount ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN debit_account  = ? THEN amount ELSE 0 END), 0)
                  FROM opslab.account_entries
                 WHERE owner_type = 'SELLER' AND owner_id = ?
                """, BigDecimal.class, account.name(), account.name(), ownerId);
    }

    private BigDecimal materialized(String ownerId, GlAccount account) {
        return jdbc.queryForObject("""
                SELECT COALESCE((SELECT balance FROM opslab.account_balances
                                  WHERE owner_type = 'SELLER' AND owner_id = ? AND account = ?), 0)
                """, BigDecimal.class, ownerId, account.name());
    }

    private long entryCount(String ownerId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM opslab.account_entries WHERE owner_type = 'SELLER' AND owner_id = ?",
                Long.class, ownerId);
    }

    @Test
    @DisplayName("여러 전표 적재 후 실체화 잔액이 원장 재합산과 일치한다 (양 레그 모두)")
    void 실체화_잔액이_원장_재합산과_일치한다() {
        String seller = "930001";
        // 즉시분 인식 2건(CR SELLER_PAYABLE) + 유보 인식 1건(CR HOLDBACK_PAYABLE) + 유보 해제 1건
        // (DR HOLDBACK_PAYABLE / CR SELLER_PAYABLE) — 한 계정이 양 레그로 모두 움직이게 구성한다.
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate(seller, "S1", new BigDecimal("100000")));
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate(seller, "S2", new BigDecimal("50000")));
        recordAccountEntryUseCase.record(
                AccountEntry.settlementHoldbackRecognized(seller, "S1", new BigDecimal("30000")));
        recordAccountEntryUseCase.record(
                AccountEntry.holdbackReleased(seller, "S1", new BigDecimal("20000")));

        // SELLER_PAYABLE = 100000 + 50000 + 20000(유보 해제 유입)
        assertThat(materialized(seller, GlAccount.SELLER_PAYABLE))
                .isEqualByComparingTo(ledgerRecomputed(seller, GlAccount.SELLER_PAYABLE))
                .isEqualByComparingTo("170000");
        // HOLDBACK_PAYABLE = 30000(인식) − 20000(해제) — 같은 계정이 대변·차변 양쪽으로 움직인 경우
        assertThat(materialized(seller, GlAccount.HOLDBACK_PAYABLE))
                .isEqualByComparingTo(ledgerRecomputed(seller, GlAccount.HOLDBACK_PAYABLE))
                .isEqualByComparingTo("10000");
        // CASH 는 차변 레그라 credit-positive 규약에서 음수로 쌓인다
        assertThat(materialized(seller, GlAccount.CASH))
                .isEqualByComparingTo(ledgerRecomputed(seller, GlAccount.CASH))
                .isEqualByComparingTo("-180000");
        // 포트 조회도 같은 값을 본다(재합산 경로 제거 후에도 의미 보존)
        assertThat(loadAccountEntryPort.sellerPayableBalance(seller)).isEqualByComparingTo("170000");
    }

    @Test
    @DisplayName("같은 자연키 재수신은 전표도 잔액도 늘리지 않는다 — 중복 기표가 잔액만 부풀리는 회귀 차단")
    void 중복_수신은_잔액을_늘리지_않는다() {
        String seller = "930002";
        AccountEntry entry = AccountEntry.settlementCreatedImmediate(seller, "DUP", new BigDecimal("70000"));

        recordAccountEntryUseCase.record(entry);
        recordAccountEntryUseCase.record(entry);   // 동일 (source_topic, ref_type, ref_id)

        assertThat(entryCount(seller)).isEqualTo(1L);
        assertThat(materialized(seller, GlAccount.SELLER_PAYABLE))
                .isEqualByComparingTo(ledgerRecomputed(seller, GlAccount.SELLER_PAYABLE))
                .isEqualByComparingTo("70000");
    }

    @Test
    @DisplayName("잔액 행이 없는 셀러는 0 — 조회가 null 을 노출하지 않는다")
    void 잔액행이_없으면_0() {
        assertThat(loadAccountEntryPort.sellerPayableBalance("930099")).isEqualByComparingTo("0");
    }
}
