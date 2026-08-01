package github.lms.lemuel.card.integration;

import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카드계정·카드 영속 계층을 실 PostgreSQL(Testcontainers)로 검증한다.
 *
 * <p>브리프가 고정한 4가지: ①한도 스냅샷 왕복 보존 ②조직당 계정 1개 유니크 위반
 * ③임직원당 활성 카드 1장 유니크 위반 ④CANCELED 후 재발급 가능(partial unique WHERE 절 동작).
 */
@SpringBootTest(
        classes = CardServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class CardPersistenceIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("card_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired LoadCardAccountPort loadCardAccountPort;
    @Autowired SaveCardAccountPort saveCardAccountPort;
    @Autowired LoadCardPort loadCardPort;
    @Autowired SaveCardPort saveCardPort;

    @BeforeEach
    void clean() {
        // cards 가 card_accounts 를 FK 로 참조하므로 자식부터. 스키마는 opslab(default_schema) —
        // 세션 search_path 에 기대지 않고 명시 접두어로 지운다.
        jdbc.execute("TRUNCATE TABLE opslab.cards, opslab.card_accounts RESTART IDENTITY CASCADE");
    }

    private CardAccount saveActiveAccount(Long organizationId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(organizationId, sellerId);
        LimitSnapshot snapshot = new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7");
        account.activate(masterLimit, snapshot);
        return saveCardAccountPort.save(account);
    }

    /** card_accounts.screened_at 원시값 조회 — 도메인/포트는 이 컬럼을 노출하지 않아 raw SQL 로 확인한다. */
    private Instant readScreenedAt(Long accountId) {
        return jdbc.queryForObject(
                "select screened_at from opslab.card_accounts where id = ?",
                (rs, rowNum) -> {
                    var ts = rs.getTimestamp("screened_at");
                    return ts == null ? null : ts.toInstant();
                },
                accountId);
    }

    @Test
    @DisplayName("카드계정 저장·조회 왕복 시 한도 스냅샷이 보존된다")
    void snapshotRoundTripPreservesLimitSnapshot() {
        LimitSnapshot snapshot = new LimitSnapshot(new BigDecimal("5000000.00"), new BigDecimal("1000000.00"),
                new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7+holdback*0.7");
        CardAccount account = CardAccount.open(4001L, "seller-1");
        account.activate(new BigDecimal("4200000.00"), snapshot);

        CardAccount saved = saveCardAccountPort.save(account);
        CardAccount reloaded = loadCardAccountPort.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
        assertThat(reloaded.getOrganizationId()).isEqualTo(4001L);
        assertThat(reloaded.getSellerId()).isEqualTo("seller-1");
        assertThat(reloaded.getMasterLimit()).isEqualByComparingTo("4200000.00");
        assertThat(reloaded.getLimitSnapshot()).isNotNull();
        assertThat(reloaded.getLimitSnapshot().sellerPayable()).isEqualByComparingTo("5000000.00");
        assertThat(reloaded.getLimitSnapshot().holdbackPayable()).isEqualByComparingTo("1000000.00");
        assertThat(reloaded.getLimitSnapshot().appliedRatio()).isEqualByComparingTo("0.7000");
        assertThat(reloaded.getLimitSnapshot().reputationGrade()).isEqualTo(ReputationGrade.B);
        assertThat(reloaded.getLimitSnapshot().formula()).isEqualTo("seller*0.7+holdback*0.7");
    }

    @Test
    @DisplayName("같은 조직에 카드계정 2개 생성 시 uq_card_account_org 위반")
    void duplicateOrgAccountViolatesUniqueConstraint() {
        saveCardAccountPort.save(CardAccount.open(4002L, "seller-2"));

        assertThatThrownBy(() -> saveCardAccountPort.save(CardAccount.open(4002L, "seller-2-dup")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 임직원에게 활성 카드 2장 발급 시 uq_card_active_holder 위반")
    void duplicateActiveCardViolatesUniqueConstraint() {
        CardAccount account = saveActiveAccount(4003L, "seller-3", new BigDecimal("1000000"));
        saveCardPort.save(Card.issue(account.getId(), 999L, "1111-****-****-1111", new BigDecimal("100000")));

        assertThatThrownBy(() -> saveCardPort.save(
                Card.issue(account.getId(), 999L, "2222-****-****-2222", new BigDecimal("100000"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("해지된 카드는 슬롯을 비운다 — 같은 임직원에게 재발급 가능")
    void canceledCardFreesTheSlot() {
        CardAccount account = saveActiveAccount(3001L, "777", new BigDecimal("1000000"));

        Card first = saveCardPort.save(Card.issue(account.getId(), 888L, "1111-****-****-1111",
                new BigDecimal("100000")));
        first.cancel();
        saveCardPort.save(first);

        Card second = saveCardPort.save(Card.issue(account.getId(), 888L, "2222-****-****-2222",
                new BigDecimal("100000")));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(loadCardPort.findActiveByHolder(account.getId(), 888L))
                .map(Card::getId).contains(second.getId());
    }

    @Test
    @DisplayName("서브한도 합계는 활성 카드만 센다 — 해지 카드가 한도를 계속 잡아먹으면 안 된다")
    void sumCountsActiveCardsOnly() {
        CardAccount account = saveActiveAccount(3002L, "778", new BigDecimal("1000000"));
        Card a = saveCardPort.save(Card.issue(account.getId(), 1L, "m1", new BigDecimal("300000")));
        saveCardPort.save(Card.issue(account.getId(), 2L, "m2", new BigDecimal("200000")));
        a.cancel();
        saveCardPort.save(a);

        assertThat(loadCardPort.sumActiveSubLimits(account.getId())).isEqualByComparingTo("200000");
    }

    @Test
    @DisplayName("서브한도 합계는 SUSPENDED 카드를 포함한다 — 정지는 일시적이라 재개 시 한도가 그대로 필요하다")
    void sumIncludesSuspendedCards() {
        CardAccount account = saveActiveAccount(3003L, "779", new BigDecimal("1000000"));
        Card a = saveCardPort.save(Card.issue(account.getId(), 11L, "m1", new BigDecimal("300000")));
        a.suspend();
        saveCardPort.save(a);
        saveCardPort.save(Card.issue(account.getId(), 12L, "m2", new BigDecimal("200000")));

        assertThat(loadCardPort.sumActiveSubLimits(account.getId())).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("신규 카드계정을 스냅샷과 함께 저장하면 screened_at 이 찍힌다")
    void screenedAtStampedOnInsert() {
        CardAccount account = saveActiveAccount(5001L, "seller-5001", new BigDecimal("1000000"));

        assertThat(readScreenedAt(account.getId())).isNotNull();
    }

    @Test
    @DisplayName("스냅샷을 그대로 두고 다른 상태만 바꿔 재저장하면 screened_at 이 갱신되지 않는다")
    void screenedAtPreservedWhenSnapshotUnchanged() throws InterruptedException {
        CardAccount account = saveActiveAccount(5002L, "seller-5002", new BigDecimal("1000000"));
        Instant firstScreenedAt = readScreenedAt(account.getId());
        assertThat(firstScreenedAt).isNotNull();

        // Instant.now() 해상도 차이로 두 저장이 같은 순간에 찍히는 것을 방지 — 버그가 재발하면
        // 이 sleep 이 있어야 "값이 달라짐"을 확실히 관측할 수 있다.
        Thread.sleep(10);

        // 스냅샷과 무관한 상태 변경(suspend) — Task 13 재산정 스케줄러가 반복 재저장하는 것과 동형.
        CardAccount reloaded = loadCardAccountPort.findById(account.getId()).orElseThrow();
        reloaded.suspend();
        saveCardAccountPort.save(reloaded);

        assertThat(readScreenedAt(account.getId())).isEqualTo(firstScreenedAt);
    }

    @Test
    @DisplayName("스냅샷이 실제로 바뀌어 재저장되면(재산정) screened_at 이 갱신된다")
    void screenedAtUpdatedWhenSnapshotChanges() throws InterruptedException {
        CardAccount account = saveActiveAccount(5003L, "seller-5003", new BigDecimal("1000000"));
        Instant firstScreenedAt = readScreenedAt(account.getId());

        Thread.sleep(10);

        // CardAccount 공개 API 는 ACTIVE 상태에서 스냅샷만 다시 바꾸는 메서드를 아직 두지 않는다
        // (activate/reject 는 SCREENING 전용, changeMasterLimit 은 스냅샷을 건드리지 않음).
        // Builder 는 "정적 팩토리와 영속성 재구성이 공용"하도록 설계됐으므로(Task 4), 재산정 유스케이스가
        // 만들어낼 결과 상태를 여기서 직접 구성해 어댑터의 diff 판정 자체를 검증한다.
        CardAccount reloaded = loadCardAccountPort.findById(account.getId()).orElseThrow();
        LimitSnapshot changedSnapshot = new LimitSnapshot(new BigDecimal("2000000.00"), BigDecimal.ZERO,
                new BigDecimal("0.8000"), ReputationGrade.A, "재산정-formula");
        CardAccount reScreened = CardAccount.builder()
                .id(reloaded.getId())
                .organizationId(reloaded.getOrganizationId())
                .sellerId(reloaded.getSellerId())
                .status(reloaded.getStatus())
                .masterLimit(reloaded.getMasterLimit())
                .limitSnapshot(changedSnapshot)
                .rejectReason(reloaded.getRejectReason())
                .version(reloaded.getVersion())
                .build();
        saveCardAccountPort.save(reScreened);

        assertThat(readScreenedAt(account.getId())).isAfter(firstScreenedAt);
    }

    @Test
    @DisplayName("음수 sellerPayable(과지급) 도 부호 그대로 왕복 보존된다 — 원장 재현성의 핵심")
    void negativeSellerPayableRoundTripsWithSignPreserved() {
        LimitSnapshot snapshot = new LimitSnapshot(new BigDecimal("-500000.00"), BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.C, "과지급 재현");
        CardAccount account = CardAccount.open(5004L, "seller-5004");
        account.activate(BigDecimal.ZERO, snapshot);

        CardAccount saved = saveCardAccountPort.save(account);
        CardAccount reloaded = loadCardAccountPort.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getLimitSnapshot().sellerPayable()).isEqualByComparingTo("-500000.00");
        assertThat(reloaded.getLimitSnapshot().funding()).isEqualByComparingTo("-500000.00");
    }
}
