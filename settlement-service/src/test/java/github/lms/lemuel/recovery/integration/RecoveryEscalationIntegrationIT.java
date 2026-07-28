package github.lms.lemuel.recovery.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.recovery.application.port.in.EscalateStaleRecoveryUseCase;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정체 채권(seed-p0-6 후속) 이관 배치 E2E — 실 PostgreSQL + 실 Flyway 마이그레이션(V20260722160000)
 * 위에서 네이티브 정체 스캔 쿼리(COALESCE 서브쿼리, tz-aware cutoff ↔ timestamp 컬럼 비교)와
 * append-only 트리거 하의 status 갱신을 검증한다.
 *
 * <p>검증(AC):
 * <ol>
 *   <li>마지막 활동(발생)이 cutoff 이전인 OPEN 채권만 MANUAL_REQUIRED 로 이관된다.</li>
 *   <li>cutoff 이후에 발생한 OPEN 채권은 그대로 OPEN 유지.</li>
 *   <li>발생은 오래됐어도 cutoff 이후 상계 이력이 있으면(최근 활동) 이관되지 않는다.</li>
 *   <li>이미 CLOSED/MANUAL_REQUIRED 인 채권은 재이관 대상이 아니다.</li>
 * </ol>
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK",
                "app.recovery.manual-escalation-days=30"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class RecoveryEscalationIntegrationIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_recovery_escalation_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("POSTGRES_USER", POSTGRES::getUsername);
        registry.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired EscalateStaleRecoveryUseCase escalateUseCase;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 8, 21, 3, 30, 0, 0, ZoneOffset.ofHours(9));
    private static final LocalDateTime STALE_CREATED = NOW.minusDays(45).toLocalDateTime(); // cutoff(30일) 이전
    private static final LocalDateTime FRESH_CREATED = NOW.minusDays(5).toLocalDateTime();  // cutoff 이내

    @Test
    @DisplayName("마지막 활동이 cutoff 이전인 OPEN 채권만 MANUAL_REQUIRED 로 이관한다")
    void escalatesOnlyRecoveriesDormantPastGracePeriod() {
        Long staleNoActivityId = seedRecovery(9101L, STALE_CREATED, "OPEN");
        Long freshId = seedRecovery(9102L, FRESH_CREATED, "OPEN");
        Long staleButRecentlyOffsetId = seedRecovery(9103L, STALE_CREATED, "OPEN");
        seedAllocation(staleButRecentlyOffsetId, 9203L, NOW.minusDays(2).toLocalDateTime()); // cutoff 이내 최근 상계
        Long alreadyClosedId = seedRecovery(9104L, STALE_CREATED, "CLOSED");

        int escalated = escalateUseCase.escalateStaleOpenRecoveries(NOW);

        assertThat(escalated).isEqualTo(1);
        assertThat(statusOf(staleNoActivityId)).isEqualTo("MANUAL_REQUIRED");
        assertThat(statusOf(freshId)).isEqualTo("OPEN");
        assertThat(statusOf(staleButRecentlyOffsetId)).isEqualTo("OPEN");
        assertThat(statusOf(alreadyClosedId)).isEqualTo("CLOSED");

        // 재실행 — 이미 이관된 건은 다시 대상이 아니므로 0건(멱등).
        assertThat(escalateUseCase.escalateStaleOpenRecoveries(NOW)).isZero();
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    /** settlement_adjustments FK 를 만족시키기 위한 최소 시딩(정산→차지백→조정) 후 seller_recoveries 직접 삽입. */
    private Long seedRecovery(Long seed, LocalDateTime createdAt, String status) {
        Long settlementId = tx.execute(s -> settlementRepo.save(newSettlement(seed)).getId());
        Long chargebackId = jdbc.queryForObject(
                "INSERT INTO chargebacks (payment_id, settlement_id, amount, reason_code, status, source, "
                        + "raised_at, created_at, updated_at) "
                        + "VALUES (?, ?, 1000.00, 'FRAUD', 'ACCEPTED', 'PG_WEBHOOK', now(), now(), now()) "
                        + "RETURNING id",
                Long.class, seed, settlementId);
        Long adjustmentId = jdbc.queryForObject(
                "INSERT INTO settlement_adjustments "
                        + "(settlement_id, chargeback_id, amount, status, adjustment_date, created_at, updated_at) "
                        + "VALUES (?, ?, -1000.00, 'CONFIRMED', ?, now(), now()) RETURNING id",
                Long.class, settlementId, chargebackId, LocalDate.now());
        return jdbc.queryForObject(
                "INSERT INTO seller_recoveries "
                        + "(source_adjustment_id, seller_id, original_amount, allocated_amount, status, created_at, closed_at) "
                        + "VALUES (?, ?, 1000.00, 0.00, ?, ?, ?) RETURNING id",
                Long.class, adjustmentId, seed, status, createdAt,
                "CLOSED".equals(status) ? createdAt : null);
    }

    private void seedAllocation(Long recoveryId, Long settlementPaymentIdSeed, LocalDateTime allocatedAt) {
        Long settlementId = tx.execute(s -> settlementRepo.save(newSettlement(settlementPaymentIdSeed)).getId());
        jdbc.update("INSERT INTO recovery_allocations (recovery_id, settlement_id, amount, created_at) "
                        + "VALUES (?, ?, 500.00, ?)",
                recoveryId, settlementId, allocatedAt);
    }

    private SettlementJpaEntity newSettlement(Long paymentId) {
        SettlementJpaEntity e = new SettlementJpaEntity();
        e.setPaymentId(paymentId);
        e.setOrderId(paymentId + 5000L);
        e.setPaymentAmount(new BigDecimal("10000.00"));
        e.setCommission(new BigDecimal("350.00"));
        e.setNetAmount(new BigDecimal("9650.00"));
        e.setStatus("DONE");
        e.setSettlementDate(LocalDate.now());
        return e;
    }

    private String statusOf(Long recoveryId) {
        return jdbc.queryForObject("SELECT status FROM seller_recoveries WHERE id = ?",
                String.class, recoveryId);
    }
}
