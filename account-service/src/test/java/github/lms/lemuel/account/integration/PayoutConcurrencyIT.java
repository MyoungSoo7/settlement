package github.lms.lemuel.account.integration;

import github.lms.lemuel.AccountServiceApplication;
import github.lms.lemuel.account.application.port.in.AccountQueryUseCase;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.application.port.in.RecordPayoutUseCase;
import github.lms.lemuel.account.application.port.out.LoadAccountEntryPort;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.account.domain.AccountSummary;
import github.lms.lemuel.account.domain.GlAccount;
import github.lms.lemuel.account.domain.OwnerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GL 감사 HIGH — payout 무락 read-then-write 경합 회귀 IT (실 PG + PG advisory xact 락).
 *
 * <p>같은 셀러에 SELLER_PAYABLE 잔액 100 을 세팅한 뒤 payout(100)·payout(100) 을 <b>두 스레드 동시</b>로
 * 실행한다. 락이 없으면 둘 다 잔액 100 을 읽어 각각 full 상계 → SELLER_PAYABLE 이 −100 으로 몰리고 초과분
 * 회수채권이 누락된다. advisory 락으로 직렬화되면 둘째가 갱신된 잔액(0)을 읽어, 최종 SELLER_PAYABLE 순잔액
 * 0 · SELLER_RECOVERY_RECEIVABLE 100 · 이중 전기 0 이 된다. 이 어서션들이 그 버그를 정확히 탐지한다.
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
class PayoutConcurrencyIT {

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

    @Autowired RecordPayoutUseCase recordPayoutUseCase;
    @Autowired RecordAccountEntryUseCase recordAccountEntryUseCase;
    @Autowired LoadAccountEntryPort loadAccountEntryPort;
    @Autowired AccountQueryUseCase accountQueryUseCase;

    private BigDecimal net(String sellerId, GlAccount account) {
        return accountQueryUseCase.accountSummary(OwnerType.SELLER, sellerId).balances().stream()
                .filter(b -> b.account() == account)
                .map(AccountSummary.Balance::balance)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private long countRefType(String sellerId, String refType) {
        return loadAccountEntryPort.findByOwner(OwnerType.SELLER, sellerId).stream()
                .filter(e -> e.getRefType().equals(refType))
                .count();
    }

    @Test
    @DisplayName("같은 셀러 동시 payout 2건이 advisory 락으로 직렬화되어 SELLER_PAYABLE 음수·이중전기 없이 초과분을 회수채권으로 인식한다")
    void concurrentPayouts_sameSeller_serializeAndRouteExcessToReceivable() throws Exception {
        final String seller = "800001"; // SELLER owner_id 는 숫자여야 함(chk_account_entry_owner_id_format)

        // 잔액 세팅: DR CASH / CR SELLER_PAYABLE 100 → SELLER_PAYABLE 순잔액 +100 (커밋 후 스레드 시작)
        recordAccountEntryUseCase.record(AccountEntry.settlementCreatedImmediate(seller, "seed-1", new BigDecimal("100")));
        assertThat(loadAccountEntryPort.sellerPayableBalance(seller)).isEqualByComparingTo("100");

        // 두 스레드가 최대한 동시에 payout(100) 을 각자 트랜잭션으로 실행
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        Runnable payout1 = payoutTask(seller, "pay-1", startGate, done, failures);
        Runnable payout2 = payoutTask(seller, "pay-2", startGate, done, failures);
        pool.submit(payout1);
        pool.submit(payout2);

        startGate.countDown();                                   // 동시 출발
        boolean finished = done.await(30, TimeUnit.SECONDS);     // 교착 방지 타임아웃
        pool.shutdownNow();

        assertThat(finished).as("두 payout 스레드가 30초 내 완료(교착 없음)").isTrue();
        assertThat(failures).as("두 payout 트랜잭션 모두 예외 없이 커밋").isEmpty();

        // ── 직렬화 결과: 첫째 full 상계(payable 100), 둘째 갱신잔액(0) 읽어 전액 회수채권 ──
        assertThat(net(seller, GlAccount.SELLER_PAYABLE)).isEqualByComparingTo("0");              // 음수 아님
        assertThat(net(seller, GlAccount.SELLER_RECOVERY_RECEIVABLE)).isEqualByComparingTo("100"); // 초과분 회수채권

        // ── 이중 전기 없음: 각 채널 정확히 1건 ──
        assertThat(countRefType(seller, "PAYOUT_COMPLETED")).isEqualTo(1L);
        assertThat(countRefType(seller, "PAYOUT_ADVANCE")).isEqualTo(1L);

        // ── 현금은 seed 100 유입·payout 200 유출 = 순 −100. 이 미회수 advance 가 회수채권 100 과 짝이다
        //    (정상방향 위반이 아니라 회수 미종료의 정상 신호 — GlCashClosedLoopIT 처럼 offset 후 0 으로 닫힘). ──
        assertThat(net(seller, GlAccount.CASH)).isEqualByComparingTo("-100");

        // ── 전사 시산표는 균형(구성적 균형이 동시성에도 보존 — 반쪽 전표 없음) ──
        assertThat(accountQueryUseCase.trialBalance().balanced()).isTrue();
    }

    private Runnable payoutTask(String seller, String payoutId, CountDownLatch startGate,
                                CountDownLatch done, List<Throwable> failures) {
        return () -> {
            try {
                startGate.await();
                recordPayoutUseCase.recordPayout(seller, payoutId, new BigDecimal("100"));
            } catch (Throwable t) {
                failures.add(t);
            } finally {
                done.countDown();
            }
        };
    }
}
