package github.lms.lemuel.integrity.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.integrity.application.port.in.IntegrityQueryUseCase;
import github.lms.lemuel.integrity.application.port.out.LoadCompletedRefundsPort;
import github.lms.lemuel.integrity.domain.ProcessedEventCount;
import github.lms.lemuel.integrity.domain.RefundAdjustmentReport;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementAdjustmentJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrity Suite Phase B 통합 검증 — INV-8 지연 환불 조정 대사 + INV-10 이벤트 회계 분자.
 * 설계: docs/design/settlement-integrity-suite.md §4 Phase B 완료 기준.
 *
 * <p>핵심 시나리오(INV-8): 캡처는 과거(D−30), 환불 완료는 최근(D−2)인 <b>지연 환불</b>은
 * 캡처일 축의 일일 대사에 잡히지 않는다. 완료일 축의 refund-adjustments 가 조정(역정산)
 * 누락을 정면으로 잡아내는 것을 입증한다. order 원천 목록은 포트 스텁으로 대체한다
 * (order 는 자기 API 로 목록만 주는 관계 — 계약은 {@code LoadCompletedRefundsPort}).
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.ledger-outbox.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@Import(IntegrityPhaseBIntegrationTest.StubCompletedRefundsConfig.class)
class IntegrityPhaseBIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    private static final LocalDate TODAY = LocalDate.now();
    /** 조정이 존재하는 환불 / 조정이 누락된 지연 환불. */
    private static final long ADJUSTED_REFUND = 901L;
    private static final long MISSING_REFUND = 902L;

    /** order 완료 환불 목록 스텁 — 캡처는 오래전, 완료는 최근인 지연 환불 2건. */
    @TestConfiguration
    static class StubCompletedRefundsConfig {
        @Bean
        @Primary
        LoadCompletedRefundsPort stubCompletedRefunds() {
            return (from, to, limit) -> List.of(
                    new LoadCompletedRefundsPort.CompletedRefund(
                            ADJUSTED_REFUND, new BigDecimal("20000.00"), TODAY.minusDays(3)),
                    new LoadCompletedRefundsPort.CompletedRefund(
                            MISSING_REFUND, new BigDecimal("15000.00"), TODAY.minusDays(2)));
        }
    }

    @Autowired IntegrityQueryUseCase integrity;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    @Test
    @DisplayName("INV-8: 완료된 지연 환불 중 조정(역정산) 없는 건을 refund_id 까지 특정한다")
    void detectsLateRefundWithoutAdjustment() {
        tx.executeWithoutResult(s -> {
            SettlementAdjustmentJpaEntity adj = new SettlementAdjustmentJpaEntity();
            adj.setSettlementId(77L);
            adj.setRefundId(ADJUSTED_REFUND); // 901 만 조정 존재 — 902 는 누락
            adj.setAmount(new BigDecimal("-20000.00"));
            adj.setStatus("CONFIRMED");
            adj.setAdjustmentDate(TODAY.minusDays(3));
            em.persist(adj);
        });

        RefundAdjustmentReport report =
                integrity.checkRefundAdjustments(TODAY.minusDays(5), TODAY.minusDays(1));

        assertThat(report.ok()).isFalse();
        assertThat(report.missingRefundIds()).containsExactly(MISSING_REFUND);
        assertThat(report.missingAmountTotal()).isEqualByComparingTo("15000.00");
        assertThat(report.completedRefunds()).isEqualTo(2);
        assertThat(report.adjustedRefunds()).isEqualTo(1);
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("INV-8"));
    }

    @Test
    @DisplayName("INV-10: processed_events 를 (consumer_group, event_type) 로 묶어 기간 건수를 노출한다")
    void exposesProcessedEventCountsGrouped() {
        LocalDateTime inRange = TODAY.minusDays(1).atTime(10, 0);
        tx.executeWithoutResult(s -> {
            insertProcessed("settlement-service", "PaymentCaptured", inRange);
            insertProcessed("settlement-service", "PaymentCaptured", inRange.plusMinutes(5));
            insertProcessed("settlement-payment-view", "PaymentCaptured", inRange);
            insertProcessed("settlement-service", "PaymentCaptured", TODAY.minusDays(30).atTime(10, 0)); // 기간 밖
        });

        List<ProcessedEventCount> counts =
                integrity.processedEventCounts(TODAY.minusDays(2), TODAY);

        assertThat(counts).anySatisfy(c -> {
            assertThat(c.consumerGroup()).isEqualTo("settlement-service");
            assertThat(c.eventType()).isEqualTo("PaymentCaptured");
            assertThat(c.count()).isEqualTo(2); // 기간 밖 1건 제외
        });
        assertThat(counts).anySatisfy(c -> {
            assertThat(c.consumerGroup()).isEqualTo("settlement-payment-view");
            assertThat(c.count()).isEqualTo(1);
        });
    }

    private void insertProcessed(String group, String eventType, LocalDateTime processedAt) {
        em.createNativeQuery("""
                        INSERT INTO processed_events (consumer_group, event_id, event_type, processed_at)
                        VALUES (?1, CAST(?2 AS uuid), ?3, ?4)
                        """)
                .setParameter(1, group)
                .setParameter(2, UUID.randomUUID().toString())
                .setParameter(3, eventType)
                .setParameter(4, processedAt)
                .executeUpdate();
    }
}
