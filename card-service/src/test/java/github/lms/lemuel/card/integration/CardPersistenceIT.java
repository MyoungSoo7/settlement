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
}
