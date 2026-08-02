package github.lms.lemuel.card.integration;

import com.fasterxml.jackson.databind.JsonNode;
import github.lms.lemuel.CardServiceApplication;
import github.lms.lemuel.card.application.port.in.RecalculateCardLimitsUseCase;
import github.lms.lemuel.card.application.port.out.LoadCardAccountPort;
import github.lms.lemuel.card.application.port.out.LoadCardPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort;
import github.lms.lemuel.card.application.port.out.LoadSellerFundingPort.SellerFunding;
import github.lms.lemuel.card.application.port.out.SaveCardAccountPort;
import github.lms.lemuel.card.application.port.out.SaveCardPort;
import github.lms.lemuel.card.application.port.out.SaveReputationPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.LimitSnapshot;
import github.lms.lemuel.card.domain.ReputationGrade;
import github.lms.lemuel.common.events.contract.EventContractValidator;
import github.lms.lemuel.common.outbox.OutboxJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Task 13 — 재산정 하향이 실 DB 위에서 Σ서브한도 하한에 걸리는지 검증한다.
 *
 * <p>단위 테스트로 대체할 수 없는 부분은 <b>클램프의 근거가 되는 합계가 어디서 오느냐</b>다.
 * {@code sumActiveSubLimits} 는 {@code status <> 'CANCELED'} 인 카드의 SQL 집계라, 목으로
 * 대체하면 "정지 카드가 합계에 남는가" 같은 결정을 테스트가 스스로 정해버린다 — 그러면
 * {@code masterLimit >= Σ서브한도} 불변식이 실제 데이터에서 성립하는지는 아무도 확인하지 않는다.
 *
 * <p>목은 재원 조회 <b>하나뿐</b>이다. 평판은 목이 아니라 실제 프로젝션 행을 심는다 —
 * {@code LoadReputationPort} 를 {@code @MockitoBean} 으로 덮으면 같은 어댑터 빈이 구현하는
 * {@code SaveReputationPort} 까지 목으로 갈려나가 컨텍스트 자체가 뜨지 않는다(한 어댑터가 두 포트를
 * 구현하는 구조에서 빈 오버라이드는 포트 단위가 아니라 <b>빈 단위</b>로 일어난다).
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
class LimitRecalculationClampIT {

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
    @Autowired RecalculateCardLimitsUseCase recalculateCardLimitsUseCase;
    @Autowired SaveCardAccountPort saveCardAccountPort;
    @Autowired SaveCardPort saveCardPort;
    @Autowired LoadCardAccountPort loadCardAccountPort;
    @Autowired LoadCardPort loadCardPort;
    @Autowired SaveReputationPort saveReputationPort;

    @MockitoBean LoadSellerFundingPort loadSellerFundingPort;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE opslab.cards, opslab.card_accounts,"
                + " opslab.org_member_projection, opslab.reputation_projection,"
                + " opslab.outbox_events RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("재원이 급감해도 마스터 한도는 Σ서브한도 아래로 내려가지 않는다")
    void downgradeIsClampedAtSubLimitSum() {
        CardAccount account = saveActiveAccount(3001L, "777", new BigDecimal("1000000"));
        issue(account, 888L, "500000");
        issue(account, 889L, "300000");
        // 700,000 x 0.70 = 490,000 → Σ서브한도 800,000 에 걸린다
        stubFunding("777", "700000", ReputationGrade.A);

        int changed = recalculateCardLimitsUseCase.recalculateAll();

        assertThat(changed).isEqualTo(1);
        assertThat(masterLimitOf(account)).isEqualByComparingTo("800000");
        assertThat(loadCardPort.sumActiveSubLimits(account.getId())).isEqualByComparingTo("800000");
        assertThat(statusOf(account)).isEqualTo(CardAccountStatus.ACTIVE);
    }

    /**
     * 클램프됐다는 사실은 DB 상태만으로는 알 수 없다 — 800,000 이 "산정값"인지 "하한에 걸린 값"인지
     * 구분되지 않는다. 그래서 {@code clamped=true} 가 이벤트에 실려야 한다.
     */
    @Test
    @DisplayName("클램프된 하향은 clamped=true 인 limit_changed 이벤트를 남긴다")
    void clampedDowngradeEmitsFlaggedEvent() {
        CardAccount account = saveActiveAccount(3002L, "778", new BigDecimal("1000000"));
        issue(account, 888L, "800000");
        stubFunding("778", "700000", ReputationGrade.A);

        recalculateCardLimitsUseCase.recalculateAll();

        List<String> payloads = payloadsOf("CardLimitChanged");
        assertThat(payloads).hasSize(1);
        EventContractValidator.assertValid("lemuel.card.limit_changed", payloads.getFirst());

        JsonNode event = parse(payloads.getFirst());
        assertThat(event.get("clamped").asBoolean()).isTrue();
        assertThat(event.get("scope").asText()).isEqualTo("MASTER");
        assertThat(event.get("cardId").isNull()).isTrue();
        assertThat(new BigDecimal(event.get("previousLimit").asText())).isEqualByComparingTo("1000000");
        assertThat(new BigDecimal(event.get("newLimit").asText())).isEqualByComparingTo("800000");
    }

    /**
     * 강등(E등급)은 한도 0 이 아니라 계정 정지로 표현되며, 그때도 클램프는 그대로 적용된다 —
     * 이미 발급된 카드의 서브한도 합만큼은 마스터에 남겨두어야 복구 시 한도가 어긋나지 않는다.
     */
    @Test
    @DisplayName("E등급 강등은 계정을 정지하되 한도는 Σ서브한도로 클램프한 채 남긴다")
    void gradeEDowngradeSuspendsAndKeepsClampedLimit() {
        CardAccount account = saveActiveAccount(3003L, "779", new BigDecimal("1000000"));
        issue(account, 888L, "400000");
        stubFunding("779", "1000000", ReputationGrade.E);

        recalculateCardLimitsUseCase.recalculateAll();

        assertThat(statusOf(account)).isEqualTo(CardAccountStatus.SUSPENDED);
        assertThat(masterLimitOf(account)).isEqualByComparingTo("400000");
        List<String> payloads = payloadsOf("CardAccountStatusChanged");
        assertThat(payloads).hasSize(1);
        EventContractValidator.assertValid("lemuel.card.account_status_changed", payloads.getFirst());

        JsonNode event = parse(payloads.getFirst());
        assertThat(event.get("previousStatus").asText()).isEqualTo("ACTIVE");
        assertThat(event.get("newStatus").asText()).isEqualTo("SUSPENDED");
        assertThat(new BigDecimal(event.get("masterLimit").asText())).isEqualByComparingTo("400000");
        assertThat(event.get("reason").asText()).isNotBlank();
    }

    /**
     * 정지된 계정은 다음 날 배치가 되살리지 않는다 — 재원이 회복돼도 복귀는 사람의 결정이다.
     * (대가는 명시적이다: 강등된 계정은 수동 {@code resume} 이 필요하다.)
     */
    @Test
    @DisplayName("정지된 계정은 재원이 회복돼도 배치가 되살리지 않는다")
    void suspendedAccountIsNotRevivedByTheBatch() {
        CardAccount account = saveActiveAccount(3004L, "780", new BigDecimal("1000000"));
        issue(account, 888L, "400000");
        stubFunding("780", "1000000", ReputationGrade.E);
        recalculateCardLimitsUseCase.recalculateAll();      // 강등
        jdbc.execute("DELETE FROM opslab.outbox_events");

        when(loadSellerFundingPort.load("780"))
                .thenReturn(new SellerFunding(new BigDecimal("5000000"), BigDecimal.ZERO));
        saveReputationPort.upsertGrade("780", ReputationGrade.A.name());

        int changed = recalculateCardLimitsUseCase.recalculateAll();

        assertThat(changed).isZero();
        assertThat(statusOf(account)).isEqualTo(CardAccountStatus.SUSPENDED);
        assertThat(masterLimitOf(account)).isEqualByComparingTo("400000");
        assertThat(payloadsOf("CardLimitChanged")).isEmpty();
    }

    @Test
    @DisplayName("재원이 늘면 상향은 Σ서브한도와 무관하게 그대로 반영된다")
    void raiseIsAppliedAsIs() {
        CardAccount account = saveActiveAccount(3005L, "781", new BigDecimal("700000"));
        issue(account, 888L, "300000");
        stubFunding("781", "3000000", ReputationGrade.A);

        int changed = recalculateCardLimitsUseCase.recalculateAll();

        assertThat(changed).isEqualTo(1);
        assertThat(masterLimitOf(account)).isEqualByComparingTo("2100000");
    }

    // ---- helpers ----

    private CardAccount saveActiveAccount(Long organizationId, String sellerId, BigDecimal masterLimit) {
        CardAccount account = CardAccount.open(organizationId, sellerId);
        account.activate(masterLimit, new LimitSnapshot(masterLimit, BigDecimal.ZERO,
                new BigDecimal("0.7000"), ReputationGrade.B, "seller*0.7"));
        return saveCardAccountPort.save(account);
    }

    private void issue(CardAccount account, Long holderUserId, String subLimit) {
        saveCardPort.save(Card.issue(account.getId(), holderUserId, "m", new BigDecimal(subLimit)));
    }

    private void stubFunding(String sellerId, String sellerPayable, ReputationGrade grade) {
        when(loadSellerFundingPort.load(sellerId))
                .thenReturn(new SellerFunding(new BigDecimal(sellerPayable), BigDecimal.ZERO));
        saveReputationPort.upsertGrade(sellerId, grade.name());
    }

    private BigDecimal masterLimitOf(CardAccount account) {
        return loadCardAccountPort.findById(account.getId()).orElseThrow().getMasterLimit();
    }

    private CardAccountStatus statusOf(CardAccount account) {
        return loadCardAccountPort.findById(account.getId()).orElseThrow().getStatus();
    }

    /**
     * jsonb 는 원문을 그대로 보관하지 않는다 — 키 순서를 바꾸고 {@code :} 뒤에 공백을 넣는다.
     * 그래서 문자열 {@code contains} 로 검증하면 발행 로직이 아니라 Postgres 의 직렬화 취향을 고정하게 된다.
     */
    private JsonNode parse(String payload) {
        try {
            return OutboxJson.mapper().readTree(payload);
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload 파싱 실패: " + payload, e);
        }
    }

    private List<String> payloadsOf(String eventType) {
        return jdbc.queryForList(
                "select payload from opslab.outbox_events where event_type = ? order by id",
                String.class, eventType);
    }
}
