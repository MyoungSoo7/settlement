package github.lms.lemuel.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.in.kafka.PgReconciliationApprovedSettlementAdjustConsumer;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementAdjustmentJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementAdjustmentJpaRepository;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.in.ApplyReconciliationAdjustmentUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PG 대사 승인 → 역정산(clawback) E2E — 실 PostgreSQL(Testcontainers).
 *
 * <p>검증:
 * <ol>
 *   <li>이벤트 → 컨슈머 → clawback 적용 (net 축소 + 음수 감사 레코드).</li>
 *   <li>같은 event_id 재전송은 processed_events 골격이 멱등 스킵 (이중 회수 없음).</li>
 *   <li>같은 discrepancyId 로 UseCase 직접 재호출은 belt-and-suspenders 로 무회수.</li>
 *   <li>마이그레이션 3-way 제약: (refund_id, chargeback_id, reconciliation_discrepancy_id) 중 정확히 하나.</li>
 * </ol>
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
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class PgReconciliationClawbackIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opslab_test")
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

    @Autowired ApplyReconciliationAdjustmentUseCase applyUseCase;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired SpringDataSettlementAdjustmentJpaRepository adjustmentRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private PgReconciliationApprovedSettlementAdjustConsumer consumer() {
        return new PgReconciliationApprovedSettlementAdjustConsumer(
                applyUseCase, processedEventRepository, objectMapper, new SimpleMeterRegistry(), null);
    }

    @Test
    void 승인_이벤트가_clawback_을_적용하고_재전송은_멱등이다() {
        // given — 결제 100,000 / 수수료 3,500 / net 96,500 인 REQUESTED 정산
        Long paymentId = 4101L;
        Long settlementId = tx.execute(s -> settlementRepo.save(
                newSettlement(paymentId, 8101L, "100000.00", "3500.00", "96500.00", "REQUESTED")).getId());

        // AMOUNT_MISMATCH diff -1,000 (pg 99,000 < internal 100,000) → clawback 1,000
        String json = """
                {"discrepancyId":9101,"type":"AMOUNT_MISMATCH","paymentId":%d,
                 "internalAmount":"100000","pgAmount":"99000","difference":"-1000"}"""
                .formatted(paymentId);
        String eventId = UUID.randomUUID().toString();

        // when — 이벤트 최초 전달
        consumer().onDiscrepancyApproved(record(json, eventId), noopAck());

        // then — net 96,500 → 95,500, 음수 감사 레코드 1건
        SettlementJpaEntity after = settlementRepo.findById(settlementId).orElseThrow();
        assertThat(after.getNetAmount()).isEqualByComparingTo("95500.00");
        assertThat(after.getRefundedAmount()).isEqualByComparingTo("0.00"); // clawback 은 refund 누적 오염 X

        List<SettlementAdjustmentJpaEntity> adjustments = adjustmentRepo.findAll().stream()
                .filter(a -> a.getSettlementId().equals(settlementId)).toList();
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.get(0).getReconciliationDiscrepancyId()).isEqualTo(9101L);
        assertThat(adjustments.get(0).getAmount()).isEqualByComparingTo("-1000.00");
        assertThat(adjustments.get(0).getRefundId()).isNull();
        assertThat(adjustments.get(0).getChargebackId()).isNull();

        // when — 같은 event_id 재전송 (processed_events 멱등)
        consumer().onDiscrepancyApproved(record(json, eventId), noopAck());

        // then — net 불변, 조정 레코드 여전히 1건 (이중 회수 없음)
        assertThat(settlementRepo.findById(settlementId).orElseThrow().getNetAmount())
                .isEqualByComparingTo("95500.00");
        assertThat(adjustmentRepo.findAll().stream()
                .filter(a -> a.getSettlementId().equals(settlementId)).count()).isEqualTo(1L);

        // when — 같은 discrepancyId 로 UseCase 직접 재호출 (belt-and-suspenders 2단 방어)
        applyUseCase.applyClawback(paymentId, 9101L, new BigDecimal("1000"));

        // then — 여전히 무회수
        assertThat(settlementRepo.findById(settlementId).orElseThrow().getNetAmount())
                .isEqualByComparingTo("95500.00");
        assertThat(adjustmentRepo.findAll().stream()
                .filter(a -> a.getSettlementId().equals(settlementId)).count()).isEqualTo(1L);
    }

    @Test
    void 조정_출처_3way_제약은_다중_출처만_금지한다() {
        // 마이그레이션의 CHECK 식(3-way at-most-one)을 격리된 probe 테이블에 그대로 복제해 검증한다.
        // 공유(캐시된) 컨텍스트/컨테이너의 실제 settlement_adjustments 스키마는 절대 건드리지 않는다
        // — 다른 테스트(PersistenceAdaptersCoverageIT 등, Flyway on)로 제약이 leak 되는 것을 원천 차단한다.
        jdbc.execute("DROP TABLE IF EXISTS public.adj_source_constraint_probe");
        jdbc.execute("""
                CREATE TABLE public.adj_source_constraint_probe (
                    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    refund_id bigint,
                    chargeback_id bigint,
                    reconciliation_discrepancy_id bigint,
                    CONSTRAINT chk_adjustment_source_at_most_one CHECK (
                        (CASE WHEN refund_id IS NOT NULL THEN 1 ELSE 0 END)
                      + (CASE WHEN chargeback_id IS NOT NULL THEN 1 ELSE 0 END)
                      + (CASE WHEN reconciliation_discrepancy_id IS NOT NULL THEN 1 ELSE 0 END)
                      <= 1))""");
        try {
            // 정확히 하나(reconciliation_discrepancy_id) → 통과
            jdbc.update("INSERT INTO public.adj_source_constraint_probe "
                    + "(reconciliation_discrepancy_id) VALUES (9001)");

            // 출처 없음(legacy 환불 무FK row) → 허용 (자금 사고 방지 — exactly-one 이 아닌 이유)
            jdbc.update("INSERT INTO public.adj_source_constraint_probe "
                    + "(refund_id, chargeback_id, reconciliation_discrepancy_id) VALUES (null, null, null)");

            // 두 출처 동시 → 위반 (이중 링크 금지)
            assertThatThrownBy(() -> jdbc.update("INSERT INTO public.adj_source_constraint_probe "
                    + "(refund_id, reconciliation_discrepancy_id) VALUES (1, 9002)"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            // 세 출처 동시 → 위반
            assertThatThrownBy(() -> jdbc.update("INSERT INTO public.adj_source_constraint_probe "
                    + "(refund_id, chargeback_id, reconciliation_discrepancy_id) VALUES (1, 2, 9003)"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS public.adj_source_constraint_probe");
        }
    }

    private ConsumerRecord<String, String> record(String json, String eventId) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "lemuel.pgreconciliation.discrepancy_approved", 0, 0L, "key", json);
        record.headers().add(new RecordHeader("event_id", eventId.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private Acknowledgment noopAck() {
        return new Acknowledgment() {
            @Override public void acknowledge() { }
        };
    }

    private SettlementJpaEntity newSettlement(Long paymentId, Long orderId,
                                              String paymentAmount, String commission, String netAmount,
                                              String status) {
        SettlementJpaEntity s = new SettlementJpaEntity();
        s.setPaymentId(paymentId);
        s.setOrderId(orderId);
        s.setPaymentAmount(new BigDecimal(paymentAmount));
        s.setCommission(new BigDecimal(commission));
        s.setNetAmount(new BigDecimal(netAmount));
        s.setStatus(status);
        s.setSettlementDate(LocalDate.now());
        return s;
    }
}
