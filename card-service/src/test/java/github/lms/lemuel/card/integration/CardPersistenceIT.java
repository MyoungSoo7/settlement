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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * 카드 코어 영속 IT — 실 PostgreSQL 에서 다음 4가지를 고정한다:
 * ① 한도 스냅샷 왕복 보존 ② 조직당 카드계정 1개(uq_card_account_org)
 * ③ 임직원당 활성 카드 1장(uq_card_active_holder) ④ CANCELED 는 슬롯을 비운다(partial unique).
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

    @Autowired SaveCardAccountPort saveCardAccountPort;
    @Autowired LoadCardAccountPort loadCardAccountPort;
    @Autowired SaveCardPort saveCardPort;
    @Autowired LoadCardPort loadCardPort;

    private static LimitSnapshot snapshot() {
        return new LimitSnapshot(new BigDecimal("1500000.00"), new BigDecimal("450000.00"),
                new BigDecimal("0.7000"), ReputationGrade.B, "min(cap, floor(F×R×H))");
    }

    private CardAccount saveActiveAccount(Long orgId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(orgId, sellerId);
        account.activate(masterLimit, snapshot());
        return saveCardAccountPort.save(account);
    }

    @Test
    @DisplayName("카드계정 저장·조회 왕복 시 한도 산정 스냅샷이 그대로 보존된다")
    void roundTripPreservesLimitSnapshot() {
        CardAccount saved = saveActiveAccount(2001L, "701", new BigDecimal("1000000"));

        CardAccount found = loadCardAccountPort.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
        assertThat(found.getMasterLimit()).isEqualByComparingTo("1000000");
        LimitSnapshot snap = found.getLimitSnapshot();
        assertThat(snap).isNotNull();
        assertThat(snap.sellerPayable()).isEqualByComparingTo("1500000.00");
        assertThat(snap.holdbackPayable()).isEqualByComparingTo("450000.00");
        assertThat(snap.appliedRatio()).isEqualByComparingTo("0.7000");
        assertThat(snap.reputationGrade()).isEqualTo(ReputationGrade.B);
        assertThat(snap.formula()).isEqualTo("min(cap, floor(F×R×H))");
        assertThat(loadCardAccountPort.findByOrganizationId(2001L)).isPresent();
    }

    @Test
    @DisplayName("같은 조직에 카드계정 2개는 uq_card_account_org 가 막는다")
    void duplicateOrgAccountIsRejected() {
        saveActiveAccount(2002L, "702", new BigDecimal("1000000"));

        assertThatThrownBy(() -> saveActiveAccount(2002L, "702-dup", new BigDecimal("500000")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 임직원에게 활성 카드 2장은 uq_card_active_holder 가 막는다")
    void duplicateActiveHolderCardIsRejected() {
        CardAccount account = saveActiveAccount(2003L, "703", new BigDecimal("1000000"));
        saveCardPort.save(Card.issue(account.getId(), 555L, "1111-****-****-1111",
                new BigDecimal("100000")));

        assertThatThrownBy(() -> saveCardPort.save(Card.issue(account.getId(), 555L,
                "2222-****-****-2222", new BigDecimal("100000"))))
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
        assertThat(loadCardPort.findByCardAccountId(account.getId())).hasSize(2);
    }
}
