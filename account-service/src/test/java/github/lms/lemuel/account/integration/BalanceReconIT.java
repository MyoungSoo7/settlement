package github.lms.lemuel.account.integration;

import github.lms.lemuel.AccountServiceApplication;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.in.TrialBalanceQuery;
import github.lms.lemuel.account.application.port.in.TrialBalanceQuery.BalanceRecon;
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
 * ADR 0030 Phase 3 — 실체화 잔액 대사가 실제로 드리프트를 검출하는지 실 PG 로 증명한다.
 *
 * <p>Phase 1({@code MaterializedBalanceIT})은 "정상 경로에서 캐시가 정답과 같다"를 증명했다.
 * 이 IT 는 반대 방향 — <b>캐시가 오염됐을 때 대사가 그것을 놓치지 않는지</b>를 세 가지 오염
 * 유형(값 왜곡·고아 캐시 행·캐시 행 유실)으로 주입해 검증한다. 대사가 못 잡는 오염 유형은
 * 운영에서 조용히 틀린 payout 라우팅으로 이어진다.
 */
@SpringBootTest(
        classes = AccountServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.recon.balance.enabled=false",   // 배치 자동 실행 차단 — 대사는 이 테스트가 직접 호출
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class BalanceReconIT {

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
    @Autowired TrialBalanceQuery trialBalanceQuery;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("정상 적재된 owner 는 드리프트에 등장하지 않는다 (다른 테스트의 오염 주입과 무관)")
    void 정상_적재는_정합이다() {
        // 같은 컨테이너를 테스트들이 공유하므로 전역 consistent 대신 owner 스코프로 단언한다 —
        // 오염 주입 테스트들이 앞서 돌았어도 정상 적재 owner 는 드리프트 목록에 없어야 한다.
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate("940001", "R1", new BigDecimal("100000")));

        BalanceRecon recon = trialBalanceQuery.balanceRecon();

        assertThat(recon.checkedPairs()).isGreaterThanOrEqualTo(2L);   // CASH + SELLER_PAYABLE
        assertThat(recon.drifts()).noneMatch(d -> "940001".equals(d.ownerId()));
    }

    @Test
    @DisplayName("캐시 값 왜곡을 주입하면 대사가 델타까지 정확히 보고한다")
    void 값_왜곡_드리프트를_검출한다() {
        recordAccountEntryUseCase.record(
                AccountEntry.settlementCreatedImmediate("940002", "R2", new BigDecimal("100000")));

        // 오염 주입: 전표 없이 잔액만 +12345.67 (컨슈머 버그·수기 UPDATE 시나리오)
        jdbc.update("""
                UPDATE opslab.account_balances SET balance = balance + 12345.67
                 WHERE owner_type = 'SELLER' AND owner_id = '940002' AND account = 'SELLER_PAYABLE'
                """);

        BalanceRecon recon = trialBalanceQuery.balanceRecon();

        assertThat(recon.consistent()).isFalse();
        assertThat(recon.drifts()).anySatisfy(d -> {
            assertThat(d.ownerType()).isEqualTo(OwnerType.SELLER);
            assertThat(d.ownerId()).isEqualTo("940002");
            assertThat(d.account()).isEqualTo(GlAccount.SELLER_PAYABLE);
            assertThat(d.materialized()).isEqualByComparingTo("112345.67");
            assertThat(d.recomputed()).isEqualByComparingTo("100000");
            assertThat(d.delta()).isEqualByComparingTo("12345.67");
        });
    }

    @Test
    @DisplayName("캐시 행 유실(원장에는 있는데 잔액 행이 없음)도 드리프트로 잡는다")
    void 캐시_행_유실을_검출한다() {
        recordAccountEntryUseCase.record(
                AccountEntry.loanDisbursed("940003", "L3", new BigDecimal("800000")));

        jdbc.update("""
                DELETE FROM opslab.account_balances
                 WHERE owner_type = 'SELLER' AND owner_id = '940003' AND account = 'LOAN_RECEIVABLE'
                """);

        BalanceRecon recon = trialBalanceQuery.balanceRecon();

        assertThat(recon.drifts()).anySatisfy(d -> {
            assertThat(d.ownerId()).isEqualTo("940003");
            assertThat(d.account()).isEqualTo(GlAccount.LOAN_RECEIVABLE);
            assertThat(d.materialized()).isEqualByComparingTo("0");         // 행 부재 = 0 간주
            assertThat(d.recomputed()).isEqualByComparingTo("-800000");     // credit-positive: 차변 레그
        });
    }

    @Test
    @DisplayName("고아 캐시 행(원장에 전표가 없는 잔액 행)도 드리프트로 잡는다")
    void 고아_캐시_행을_검출한다() {
        jdbc.update("""
                INSERT INTO opslab.account_balances (owner_type, owner_id, account, balance, updated_at)
                VALUES ('SELLER', '940004', 'CASH', 99999.00, NOW())
                """);

        BalanceRecon recon = trialBalanceQuery.balanceRecon();

        assertThat(recon.drifts()).anySatisfy(d -> {
            assertThat(d.ownerId()).isEqualTo("940004");
            assertThat(d.account()).isEqualTo(GlAccount.CASH);
            assertThat(d.materialized()).isEqualByComparingTo("99999.00");
            assertThat(d.recomputed()).isEqualByComparingTo("0");
        });
    }
}
