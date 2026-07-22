package github.lms.lemuel.recovery.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.payout.application.port.in.RequestPayoutUseCase;
import github.lms.lemuel.payout.domain.PayoutType;
import github.lms.lemuel.recovery.application.port.in.OffsetSellerRecoveryUseCase;
import github.lms.lemuel.recovery.application.port.in.RecordPostPayoutRecoveryUseCase;
import github.lms.lemuel.recovery.domain.SellerRecovery;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.out.LoadSellerIdPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시드 P0-6 지급후 회수 채권·상계 E2E — 실 PostgreSQL + 실 Flyway 마이그레이션(V20260722160000 검증).
 *
 * <p>검증(AC):
 * <ol>
 *   <li>COMPLETED Payout 정산의 차지백 회수 → holdback 흡수 후 잔여만 채권, Payout·정산 원본 불변.</li>
 *   <li>후속 정산 확정 시 미상계 채권이 지급액에서 상계되고 이력이 남는다.</li>
 *   <li>발생·상계 각각 균형 원장 분개 1:1 (Dr AR/Cr AP · Dr AP/Cr AR, POSTED).</li>
 *   <li>발생·상계 모두 재실행 시 2회차 변경 0건(멱등).</li>
 *   <li>append-only DB 트리거 — 상계 이력 UPDATE 는 DB 가 거부한다.</li>
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
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@Import(PostPayoutRecoveryIntegrationIT.StubSellerConfig.class)
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class PostPayoutRecoveryIntegrationIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_recovery_test")
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

    /** 결제→셀러 해석 스텁 — 정산별 유일 셀러(paymentId+8000)로 테스트 간 교차 오염을 차단한다. */
    @TestConfiguration
    static class StubSellerConfig {
        @Bean
        @Primary
        LoadSellerIdPort stubLoadSellerIdPort() {
            return paymentId -> Optional.of(paymentId + 8000L);
        }
    }

    @Autowired RecordPostPayoutRecoveryUseCase recordUseCase;
    @Autowired OffsetSellerRecoveryUseCase offsetUseCase;
    @Autowired RequestPayoutUseCase requestPayoutUseCase;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("발생 — holdback 흡수 후 잔여만 채권 + 발생 분개(AR/AP) 1건, 원본 불변, 재실행 0건")
    void opensReceivableForPostPayoutChargeback() {
        LocalDate date = LocalDate.now();
        Long settlementId = seedDoneSettlementWithCompletedPayout(6101L, date, "1000.00");
        Long adjustmentId = seedChargebackAdjustment(settlementId, 6101L, "-3000.00", date);

        // 최초 실행 — 3,000 회수 중 holdback 1,000 흡수, 잔여 2,000 채권
        Optional<SellerRecovery> first = recordUseCase.recordIfPostPayout(
                settlementId, adjustmentId, new BigDecimal("3000.00"), date);

        assertThat(first).isPresent();
        assertThat(first.get().getOriginalAmount()).isEqualByComparingTo("2000.00");
        Long recoveryId = first.get().getId();

        // 정산 원본 불변(DONE·net) + holdback 만 흡수로 소진
        assertThat(jdbc.queryForMap("SELECT status, net_amount, holdback_amount FROM settlements WHERE id = ?",
                settlementId))
                .satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("DONE");
                    assertThat((BigDecimal) row.get("net_amount")).isEqualByComparingTo("96500.00");
                    assertThat((BigDecimal) row.get("holdback_amount")).isEqualByComparingTo("0.00");
                });
        // COMPLETED Payout 불변
        assertThat(jdbc.queryForMap("SELECT status, amount FROM payouts WHERE settlement_id = ?", settlementId))
                .satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("COMPLETED");
                    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("95500.00");
                });
        // 발생 분개 1:1 — Dr AR / Cr AP, 잔여 금액, POSTED
        assertThat(jdbc.queryForMap("SELECT debit_account, credit_account, amount, status, entry_type "
                        + "FROM ledger_entries WHERE reference_type = 'SELLER_RECOVERY' AND reference_id = ?",
                recoveryId))
                .satisfies(row -> {
                    assertThat(row.get("debit_account")).isEqualTo("ACCOUNTS_RECEIVABLE");
                    assertThat(row.get("credit_account")).isEqualTo("ACCOUNTS_PAYABLE");
                    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("2000.00");
                    assertThat(row.get("status")).isEqualTo("POSTED");
                    assertThat(row.get("entry_type")).isEqualTo("RECOVERY_RECOGNIZED");
                });

        // 재실행 — 2회차 변경 0건 (조정 1건=채권 1건 멱등)
        assertThat(recordUseCase.recordIfPostPayout(settlementId, adjustmentId,
                new BigDecimal("3000.00"), date)).isEmpty();
        assertThat(countRecoveries(adjustmentId)).isEqualTo(1);
        assertThat(countLedger("SELLER_RECOVERY", recoveryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("상계 — 후속 정산 지급액에서 오래된 순 소진 + 상계 분개(AP/AR) 1건, 재실행 재사용, 트리거 불변")
    void offsetsReceivableAgainstNextSettlement() {
        LocalDate date = LocalDate.now();
        Long paidSettlement = seedDoneSettlementWithCompletedPayout(6201L, date, "0.00");
        Long adjustmentId = seedChargebackAdjustment(paidSettlement, 6201L, "-3000.00", date);
        Long sellerId = 6201L + 8000L; // 스텁 규칙과 동일
        SellerRecovery recovery = recordUseCase.recordIfPostPayout(
                paidSettlement, adjustmentId, new BigDecimal("3000.00"), date).orElseThrow();

        // 후속 정산(같은 셀러, 다른 결제)의 확정 — 지급가능 5,000 중 3,000 상계
        Long nextSettlement = tx.execute(s -> settlementRepo.save(
                newSettlement(6202L, 9202L, "5000.00", "175.00", "4825.00", "REQUESTED")).getId());
        BigDecimal offset = offsetUseCase.offsetForConfirmedSettlement(
                nextSettlement, sellerId, new BigDecimal("5000.00"), date);

        assertThat(offset).isEqualByComparingTo("3000.00");
        // 채권 전액 상계 → CLOSED, 이력 1건
        assertThat(jdbc.queryForMap("SELECT status, allocated_amount FROM seller_recoveries WHERE id = ?",
                recovery.getId()))
                .satisfies(row -> {
                    assertThat(row.get("status")).isEqualTo("CLOSED");
                    assertThat((BigDecimal) row.get("allocated_amount")).isEqualByComparingTo("3000.00");
                });
        Long allocationId = jdbc.queryForObject(
                "SELECT id FROM recovery_allocations WHERE recovery_id = ? AND settlement_id = ?",
                Long.class, recovery.getId(), nextSettlement);
        // 상계 분개 1:1 — Dr AP / Cr AR, POSTED
        assertThat(jdbc.queryForMap("SELECT debit_account, credit_account, amount, status, entry_type "
                        + "FROM ledger_entries WHERE reference_type = 'RECOVERY_OFFSET' AND reference_id = ?",
                allocationId))
                .satisfies(row -> {
                    assertThat(row.get("debit_account")).isEqualTo("ACCOUNTS_PAYABLE");
                    assertThat(row.get("credit_account")).isEqualTo("ACCOUNTS_RECEIVABLE");
                    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("3000.00");
                    assertThat(row.get("status")).isEqualTo("POSTED");
                    assertThat(row.get("entry_type")).isEqualTo("RECOVERY_OFFSET");
                });

        // 재실행 — 기존 상계 총액 재사용, 새 이력·분개 없음
        assertThat(offsetUseCase.offsetForConfirmedSettlement(
                nextSettlement, sellerId, new BigDecimal("5000.00"), date))
                .isEqualByComparingTo("3000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recovery_allocations WHERE settlement_id = ?",
                Long.class, nextSettlement)).isEqualTo(1L);
        assertThat(countLedger("RECOVERY_OFFSET", allocationId)).isEqualTo(1);

        // append-only 트리거 — 상계 이력 UPDATE 는 DB 가 거부한다
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE recovery_allocations SET amount = 1 WHERE id = ?", allocationId))
                .isInstanceOf(DataAccessException.class);
    }

    // ───────────────────────────── fixtures ─────────────────────────────

    /** DONE 정산(payment 100,000 / net 96,500) + 즉시지급 Payout(COMPLETED, 95,500=net−holdback) 시딩. */
    private Long seedDoneSettlementWithCompletedPayout(Long paymentId, LocalDate date, String holdback) {
        Long id = tx.execute(s -> settlementRepo.save(
                newSettlement(paymentId, paymentId + 3000L, "100000.00", "3500.00", "96500.00", "DONE")).getId());
        jdbc.update("UPDATE settlements SET confirmed_at = ?, holdback_amount = ?::numeric, "
                + "holdback_released = false WHERE id = ?", date.atTime(9, 0), holdback, id);
        BigDecimal immediate = new BigDecimal("96500.00").subtract(new BigDecimal(holdback));
        requestPayoutUseCase.requestPayoutOfType(id, paymentId + 8000L, immediate, PayoutType.IMMEDIATE);
        jdbc.update("UPDATE payouts SET status = 'COMPLETED' WHERE settlement_id = ?", id);
        return id;
    }

    private Long seedChargebackAdjustment(Long settlementId, Long paymentId, String amount, LocalDate date) {
        // 실 스키마 FK(fk_adjustments_chargeback) — ACCEPTED 분쟁 원본을 먼저 시딩한다.
        Long chargebackId = jdbc.queryForObject(
                "INSERT INTO chargebacks (payment_id, settlement_id, amount, reason_code, status, source, "
                        + "raised_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?::numeric, 'FRAUD', 'ACCEPTED', 'PG_WEBHOOK', now(), now(), now()) "
                        + "RETURNING id",
                Long.class, paymentId, settlementId, amount.replace("-", ""));
        jdbc.update("INSERT INTO settlement_adjustments "
                        + "(settlement_id, chargeback_id, amount, status, adjustment_date, created_at, updated_at) "
                        + "VALUES (?, ?, ?::numeric, 'CONFIRMED', ?, now(), now())",
                settlementId, chargebackId, amount, date);
        return jdbc.queryForObject("SELECT id FROM settlement_adjustments WHERE chargeback_id = ?",
                Long.class, chargebackId);
    }

    private SettlementJpaEntity newSettlement(Long paymentId, Long orderId, String payment,
                                              String commission, String net, String status) {
        SettlementJpaEntity e = new SettlementJpaEntity();
        e.setPaymentId(paymentId);
        e.setOrderId(orderId);
        e.setPaymentAmount(new BigDecimal(payment));
        e.setCommission(new BigDecimal(commission));
        e.setNetAmount(new BigDecimal(net));
        e.setStatus(status);
        e.setSettlementDate(LocalDate.now());
        return e;
    }

    private int countRecoveries(Long adjustmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM seller_recoveries WHERE source_adjustment_id = ?",
                Integer.class, adjustmentId);
    }

    private int countLedger(String referenceType, Long referenceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE reference_type = ? AND reference_id = ?",
                Integer.class, referenceType, referenceId);
    }
}
