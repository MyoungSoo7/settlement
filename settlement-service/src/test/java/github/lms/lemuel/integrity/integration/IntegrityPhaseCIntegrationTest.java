package github.lms.lemuel.integrity.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.integrity.application.port.in.ProjectionReconciliationUseCase;
import github.lms.lemuel.integrity.application.port.out.KeyChecksum;
import github.lms.lemuel.integrity.application.port.out.LoadOrderPaymentKeysPort;
import github.lms.lemuel.integrity.application.port.out.PaymentKey;
import github.lms.lemuel.integrity.domain.ProjectionDiffReport;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementPaymentViewJpaEntity;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrity Suite Phase C 통합 검증 — INV-12 프로젝션 행 diff.
 * 설계: docs/design/settlement-integrity-suite.md §4 Phase C 완료 기준
 * ("프로젝션 뷰에서 행 1건 삭제 시 누락 id 를 특정해 보고").
 *
 * <p>시나리오: order 원천엔 결제 3건({1,2,3})이 있는데 settlement 프로젝션(settlement_payment_view)엔
 * 2건({1,2})만 있다(3번이 유실/삭제된 상황). 하이브리드 대사가 체크섬 불일치를 감지하고, 키 diff 로
 * 누락 payment_id = 3 을 정확히 짚어내는 것을 입증한다. order 측은 자기 API 로 키만 주는 관계라
 * 포트({@link LoadOrderPaymentKeysPort})를 스텁으로 대체한다(프로젝션 측은 실제 settlement_db 조회).
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
@Import(IntegrityPhaseCIntegrationTest.StubOrderKeysConfig.class)
class IntegrityPhaseCIntegrationTest {

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

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    /** order 원천 결제 키 스텁 — 3건({1,2,3}). 프로젝션은 실제 DB 에 2건만 적재해 불일치를 만든다. */
    @TestConfiguration
    static class StubOrderKeysConfig {
        @Bean
        @Primary
        LoadOrderPaymentKeysPort stubOrderKeys() {
            return new LoadOrderPaymentKeysPort() {
                @Override
                public KeyChecksum checksum(LocalDate date) {
                    // 프로젝션(2건)과 다른 요약 → 반드시 키 diff 로 진입시킨다.
                    return new KeyChecksum(3L, new BigDecimal("3000.00"), "order-side-hash");
                }

                @Override
                public List<PaymentKey> keys(LocalDate date, long afterId, int limit) {
                    return List.of(
                            new PaymentKey(1L, new BigDecimal("1000.00")),
                            new PaymentKey(2L, new BigDecimal("1000.00")),
                            new PaymentKey(3L, new BigDecimal("1000.00")))
                            .stream().filter(k -> k.paymentId() > afterId).limit(limit).toList();
                }
            };
        }
    }

    @Autowired ProjectionReconciliationUseCase projection;
    @Autowired TransactionTemplate tx;
    @PersistenceContext EntityManager em;

    @Test
    @DisplayName("INV-12: 프로젝션에 없는 결제 행의 payment_id 를 특정한다")
    void pinpointsMissingProjectionRow() {
        tx.executeWithoutResult(s -> {
            persistProjection(1L, new BigDecimal("1000.00"));
            persistProjection(2L, new BigDecimal("1000.00"));
            // payment_id 3 은 일부러 적재하지 않음 — 유실/삭제된 행
        });

        ProjectionDiffReport report = projection.reconcileProjection(DATE, "payment", null);

        assertThat(report.ok()).isFalse();
        assertThat(report.checksumMatched()).isFalse();
        assertThat(report.missingInProjectionIds()).containsExactly(3L);
        assertThat(report.missingInProjectionCount()).isEqualTo(1L);
        assertThat(report.missingInProjectionAmount()).isEqualByComparingTo("1000.00");
        assertThat(report.orphanInProjectionCount()).isZero();
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("INV-12"));
    }

    private void persistProjection(long paymentId, BigDecimal amount) {
        LocalDateTime capturedAt = DATE.atTime(12, 0);
        SettlementPaymentViewJpaEntity v = new SettlementPaymentViewJpaEntity();
        v.setPaymentId(paymentId);
        v.setOrderId(paymentId);
        v.setAmount(amount);
        v.setStatus("CAPTURED");
        v.setCapturedAt(capturedAt);
        v.setUpdatedAt(capturedAt);
        em.persist(v);
    }
}
