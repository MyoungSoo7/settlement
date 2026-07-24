package github.lms.lemuel.settlement.integration;

import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.chargeback.adapter.out.persistence.ChargebackPersistenceAdapter;
import github.lms.lemuel.chargeback.domain.Chargeback;
import github.lms.lemuel.chargeback.domain.ChargebackReason;
import github.lms.lemuel.chargeback.domain.ChargebackSource;
import github.lms.lemuel.chargeback.domain.ChargebackStatus;
import github.lms.lemuel.ledger.adapter.out.persistence.LedgerOutboxPersistenceAdapter;
import github.lms.lemuel.ledger.adapter.out.persistence.LedgerPersistenceAdapter;
import github.lms.lemuel.ledger.domain.AccountType;
import github.lms.lemuel.ledger.domain.LedgerEntry;
import github.lms.lemuel.ledger.domain.LedgerEntryType;
import github.lms.lemuel.ledger.domain.LedgerOutboxTask;
import github.lms.lemuel.ledger.domain.ReferenceType;
import github.lms.lemuel.payout.adapter.out.persistence.PayoutPersistenceAdapter;
import github.lms.lemuel.payout.domain.Payout;
import github.lms.lemuel.payout.domain.PayoutStatus;
import github.lms.lemuel.payout.domain.SellerBankAccount;
import github.lms.lemuel.pgreconciliation.adapter.out.event.PgReconciliationOutboxEventAdapter;
import github.lms.lemuel.pgreconciliation.adapter.out.persistence.PgReconciliationPersistenceAdapter;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyStatus;
import github.lms.lemuel.pgreconciliation.domain.DiscrepancyType;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRun;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationRunStatus;
import github.lms.lemuel.settlement.adapter.in.event.dto.SettlementIndexEvent;
import github.lms.lemuel.settlement.adapter.out.event.SettlementEventPublisherAdapter;
import github.lms.lemuel.settlement.adapter.out.event.SettlementKafkaEventPublisherAdapter;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementAdjustmentJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementBatchHealthPersistenceAdapter;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementIndexQueueJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementLoanDeductionPersistenceAdapter;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementAdjustmentJpaRepository;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementIndexQueueRepository;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.dto.SettlementBatchHealthSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * settlement-service 의 chargeback/payout/pgreconciliation/ledger/settlement 영속성 어댑터 +
 * JPA 엔티티 매핑 + outbox 이벤트 어댑터를 실제 PostgreSQL(Testcontainers)로 검증한다.
 *
 * <p>JaCoCo LINE 커버리지 확대 목적 — save/find/update 경로를 실제 구동해 어댑터 매핑 코드와
 * 엔티티 getter/setter 를 실행한다. Flyway 는 전체 마이그레이션(V1 베이스라인 포함)을 적용해
 * ddl-auto=validate 로 스키마 일치를 보장한다.
 */
@SpringBootTest(
        classes = SettlementServiceApplication.class,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration",
                "spring.flyway.schemas=public",
                "spring.flyway.default-schema=public",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=public",
                "app.kafka.enabled=false",
                "app.search.enabled=false",
                "spring.batch.job.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        })
@Testcontainers
@RecordApplicationEvents
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class PersistenceAdaptersCoverageIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_db").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ChargebackPersistenceAdapter chargebackAdapter;
    @Autowired PayoutPersistenceAdapter payoutAdapter;
    @Autowired PgReconciliationPersistenceAdapter pgReconAdapter;
    @Autowired PgReconciliationOutboxEventAdapter pgReconOutboxAdapter;
    @Autowired SettlementEventPublisherAdapter settlementEventPublisherAdapter;
    @Autowired SettlementKafkaEventPublisherAdapter settlementKafkaEventPublisherAdapter;
    @Autowired LedgerPersistenceAdapter ledgerPersistenceAdapter;
    @Autowired LedgerOutboxPersistenceAdapter ledgerOutboxPersistenceAdapter;
    @Autowired SettlementBatchHealthPersistenceAdapter batchHealthAdapter;
    @Autowired SettlementLoanDeductionPersistenceAdapter loanDeductionAdapter;
    @Autowired SpringDataSettlementIndexQueueRepository indexQueueRepository;
    @Autowired SpringDataSettlementJpaRepository settlementRepository;
    @Autowired SpringDataSettlementAdjustmentJpaRepository adjustmentRepository;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM pg_reconciliation_discrepancies");
        jdbc.update("DELETE FROM pg_reconciliation_runs");
        jdbc.update("DELETE FROM chargebacks");
        jdbc.update("DELETE FROM ledger_outbox");
        jdbc.update("DELETE FROM settlement_loan_deductions");
        jdbc.update("DELETE FROM settlement_index_queue");
        // 정산/원장/지급 이력 테이블은 immutable-history-guard 가 DELETE 를 금지한다(운영 불변 원칙).
        // 테스트 컨테이너 격리 초기화는 TRUNCATE 로 수행 (CASCADE: FK 참조 정리, RESTART IDENTITY: 시퀀스 초기화).
        jdbc.execute("TRUNCATE TABLE payouts, ledger_entries, settlement_adjustments, settlements RESTART IDENTITY CASCADE");
    }

    /**
     * 내부 FK 복원(V20260715110000) 이후 payouts/chargebacks/ledger_outbox/settlement_loan_deductions/
     * settlement_index_queue 의 settlement_id 는 실존 부모 정산을 요구한다 — 자식 행을 만드는 테스트는
     * 이 헬퍼로 부모 정산을 먼저 시드한다. (payment_id 는 UNIQUE 라 id 와 동일 값 사용.)
     */
    private void seedParentSettlement(long settlementId) {
        jdbc.update("""
                INSERT INTO settlements
                  (id, payment_id, order_id, payment_amount, refunded_amount, commission, commission_rate,
                   net_amount, holdback_amount, holdback_rate, holdback_released, settlement_date, status,
                   version, created_at, updated_at)
                VALUES (?, ?, ?, 10000.00, 0.00, 300.00, 0.0300, 9700.00, 0.00, 0.0000, false,
                        CURRENT_DATE, 'REQUESTED', 0, now(), now())
                """, settlementId, settlementId, settlementId + 1);
    }

    // ========== Chargeback ==========

    @Test
    @DisplayName("Chargeback: 신규 저장 후 findById/findByPaymentId/findByStatus 로 조회된다")
    void chargeback_save_and_find() {
        Chargeback opened = Chargeback.open(5001L, null, new BigDecimal("15000.00"),
                ChargebackReason.FRAUD, "미인지 결제 신고", ChargebackSource.MANUAL, null);

        Chargeback saved = chargebackAdapter.save(opened);

        assertThat(saved.getId()).isNotNull();
        assertThat(chargebackAdapter.findById(saved.getId())).isPresent();
        assertThat(chargebackAdapter.findByPaymentId(5001L)).hasSize(1);
        assertThat(chargebackAdapter.findByStatus(ChargebackStatus.OPEN, 10)).hasSize(1);
    }

    @Test
    @DisplayName("Chargeback: PG_WEBHOOK 출처는 pgChargebackId 로 조회된다")
    void chargeback_findByPgChargebackId() {
        Chargeback opened = Chargeback.open(5002L, null, new BigDecimal("2000.00"),
                ChargebackReason.DUPLICATE, null, ChargebackSource.PG_WEBHOOK, "PG-TXN-9001");

        chargebackAdapter.save(opened);

        Optional<Chargeback> found = chargebackAdapter.findByPgChargebackId("PG-TXN-9001");
        assertThat(found).isPresent();
        assertThat(found.get().getSource()).isEqualTo(ChargebackSource.PG_WEBHOOK);
        assertThat(chargebackAdapter.findByPgChargebackId(null)).isEmpty();
    }

    @Test
    @DisplayName("Chargeback: accept() 결정 후 재저장하면 상태/결정자/결정일이 갱신된다 (update 경로)")
    void chargeback_accept_updatesDecision() {
        Chargeback saved = chargebackAdapter.save(
                Chargeback.open(5003L, null, new BigDecimal("3000.00"),
                        ChargebackReason.NOT_RECEIVED, "상품 미수령", ChargebackSource.MANUAL, null));

        saved.accept("ops-alice", "셀러 책임 인정 — 환수 처리");
        Chargeback updated = chargebackAdapter.save(saved);

        assertThat(updated.getStatus()).isEqualTo(ChargebackStatus.ACCEPTED);
        assertThat(updated.getDecidedBy()).isEqualTo("ops-alice");
        assertThat(updated.getDecidedAt()).isNotNull();

        Chargeback reloaded = chargebackAdapter.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChargebackStatus.ACCEPTED);
        assertThat(reloaded.getDecisionNote()).isEqualTo("셀러 책임 인정 — 환수 처리");
    }

    @Test
    @DisplayName("Chargeback: linkSettlement() 후 재저장하면 settlementId 만 갱신되고 상태는 OPEN 유지 (settlementId!=null 보존 분기)")
    void chargeback_linkSettlement_preservesOpenStatus() {
        seedParentSettlement(777L);
        Chargeback saved = chargebackAdapter.save(
                Chargeback.open(5004L, null, new BigDecimal("500.00"),
                        ChargebackReason.OTHER, null, ChargebackSource.MANUAL, null));
        assertThat(saved.getSettlementId()).isNull();

        saved.linkSettlement(777L);
        Chargeback updated = chargebackAdapter.save(saved);

        assertThat(updated.getStatus()).isEqualTo(ChargebackStatus.OPEN);
        assertThat(updated.getSettlementId()).isEqualTo(777L);
    }

    // ========== Payout ==========

    private static final SellerBankAccount ACCOUNT =
            new SellerBankAccount("KB", "110-222-333444", "홍길동");

    @Test
    @DisplayName("Payout: 신규 저장 후 findById/findBySettlementId/findByStatus 로 조회된다")
    void payout_save_and_find() {
        seedParentSettlement(9001L);
        Payout requested = Payout.requestFromSettlement(9001L, 42L, new BigDecimal("70000"), ACCOUNT);

        Payout saved = payoutAdapter.save(requested);

        assertThat(saved.getId()).isNotNull();
        assertThat(payoutAdapter.findById(saved.getId())).isPresent();
        assertThat(payoutAdapter.findBySettlementId(9001L)).isPresent();
        assertThat(payoutAdapter.findByStatus(PayoutStatus.REQUESTED, 10)).hasSize(1);
    }

    @Test
    @DisplayName("Payout: startSending → markCompleted 후 재저장하면 상태/거래ID 가 갱신된다 (update 경로)")
    void payout_lifecycle_update() {
        seedParentSettlement(9002L);
        Payout saved = payoutAdapter.save(
                Payout.requestFromSettlement(9002L, 43L, new BigDecimal("30000"), ACCOUNT));

        saved.startSending();
        payoutAdapter.save(saved);
        saved.markCompleted("FB-TXN-777");
        Payout completed = payoutAdapter.save(saved);

        assertThat(completed.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
        assertThat(completed.getFirmBankingTransactionId()).isEqualTo("FB-TXN-777");

        Payout reloaded = payoutAdapter.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PayoutStatus.COMPLETED);
    }

    @Test
    @DisplayName("Payout: claimForSending 은 REQUESTED 를 원자적으로 SENDING 으로 선점하고, 재호출은 빈 값")
    void payout_claimForSending_atomicClaim() {
        seedParentSettlement(9003L);
        Payout saved = payoutAdapter.save(
                Payout.requestFromSettlement(9003L, 44L, new BigDecimal("12000"), ACCOUNT));

        // claimForSending 은 @Modifying JPQL UPDATE — 반드시 활성 트랜잭션 안에서 호출.
        Optional<Payout> claimed = transactionTemplate.execute(
                status -> payoutAdapter.claimForSending(saved.getId()));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().getStatus()).isEqualTo(PayoutStatus.SENDING);

        Optional<Payout> secondClaim = transactionTemplate.execute(
                status -> payoutAdapter.claimForSending(saved.getId()));
        assertThat(secondClaim).isEmpty();
    }

    @Test
    @DisplayName("Payout: sumCompletedBySellerOn/sumCompletedSystemwideOn 이 당일 COMPLETED 금액만 합산한다")
    void payout_sumCompleted() {
        LocalDate today = LocalDate.now();
        seedParentSettlement(9004L);
        seedParentSettlement(9005L);
        Payout p1 = payoutAdapter.save(Payout.requestFromSettlement(9004L, 50L, new BigDecimal("10000"), ACCOUNT));
        p1.startSending(); p1.markCompleted("FB-1");
        payoutAdapter.save(p1);

        Payout p2 = payoutAdapter.save(Payout.requestFromSettlement(9005L, 50L, new BigDecimal("5000"), ACCOUNT));
        p2.startSending(); p2.markCompleted("FB-2");
        payoutAdapter.save(p2);

        BigDecimal bySeller = payoutAdapter.sumCompletedBySellerOn(50L, today);
        BigDecimal systemwide = payoutAdapter.sumCompletedSystemwideOn(today);

        assertThat(bySeller).isEqualByComparingTo("15000");
        assertThat(systemwide).isGreaterThanOrEqualTo(bySeller);
        assertThat(payoutAdapter.sumCompletedBySellerOn(999L, today)).isEqualByComparingTo("0");
    }

    // ========== PgReconciliation ==========

    @Test
    @DisplayName("PgReconciliation: saveAll 로 run+discrepancy 를 함께 저장하고 findById/findRecent 로 조회된다")
    void pgReconciliation_saveAll_and_find() {
        LocalDate targetDate = LocalDate.of(2026, 6, 1);
        ReconciliationRun run = ReconciliationRun.start("TOSS", targetDate, "toss-20260601.csv", "ops-bob");
        ReconciliationDiscrepancy discrepancy = ReconciliationDiscrepancy.newDiscrepancy(
                0L, DiscrepancyType.AMOUNT_MISMATCH, 6001L, "PG-TXN-1",
                new BigDecimal("10000.00"), new BigDecimal("10500.00"));
        run.complete(10, 10, 9, List.of(discrepancy));

        ReconciliationRun saved = pgReconAdapter.saveAll(run);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ReconciliationRunStatus.COMPLETED);
        assertThat(saved.getDiscrepancies()).hasSize(1);
        assertThat(saved.getDiscrepancies().get(0).getRunId()).isEqualTo(saved.getId());

        ReconciliationRun reloaded = pgReconAdapter.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDiscrepancies()).hasSize(1);
        assertThat(reloaded.getDiscrepancies().get(0).getType()).isEqualTo(DiscrepancyType.AMOUNT_MISMATCH);

        assertThat(pgReconAdapter.findRecent(10)).hasSize(1);
    }

    @Test
    @DisplayName("PgReconciliation: 실행 실패(fail) 시 FAILED 상태로 저장된다")
    void pgReconciliation_failedRun() {
        ReconciliationRun run = ReconciliationRun.start("TOSS", LocalDate.now(), "bad.csv", "ops-carol");
        run.fail("파일 파싱 실패: 잘못된 형식");

        ReconciliationRun saved = pgReconAdapter.saveAll(run);

        assertThat(saved.getStatus()).isEqualTo(ReconciliationRunStatus.FAILED);
        assertThat(saved.getNote()).contains("파일 파싱 실패");
    }

    @Test
    @DisplayName("PgReconciliation: 단건 discrepancy save/approve 후 findDiscrepancyById 로 조회, outbox 이벤트가 발행된다")
    void pgReconciliation_discrepancy_approve_and_publish() {
        ReconciliationRun run = ReconciliationRun.start("TOSS", LocalDate.now(), "f.csv", "ops-dave");
        ReconciliationDiscrepancy d = ReconciliationDiscrepancy.newDiscrepancy(
                0L, DiscrepancyType.MISSING_INTERNAL, null, "PG-TXN-2",
                null, new BigDecimal("3000.00"));
        run.complete(1, 0, 0, List.of(d));
        ReconciliationRun saved = pgReconAdapter.saveAll(run);

        ReconciliationDiscrepancy persisted = saved.getDiscrepancies().get(0);
        assertThat(persisted.getStatus()).isEqualTo(DiscrepancyStatus.PENDING);

        persisted.approve("ops-dave", "확인 완료 — 내부 누락 보정 필요");
        ReconciliationDiscrepancy updated = pgReconAdapter.save(persisted);

        assertThat(updated.getStatus()).isEqualTo(DiscrepancyStatus.APPROVED);
        assertThat(pgReconAdapter.findDiscrepancyById(persisted.getId()).orElseThrow().getResolvedBy())
                .isEqualTo("ops-dave");

        pgReconOutboxAdapter.publishDiscrepancyApproved(updated);

        Long outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'PgReconciliation' " +
                        "AND event_type = 'PgReconciliationDiscrepancyApproved'", Long.class);
        assertThat(outboxCount).isEqualTo(1L);
    }

    // ========== Settlement Event Publishers ==========

    @Test
    @DisplayName("SettlementEventPublisherAdapter: BATCH_CREATED/BATCH_CONFIRMED ApplicationEvent 를 발행한다")
    void settlementEventPublisherAdapter_publishesApplicationEvents(ApplicationEvents events) {
        settlementEventPublisherAdapter.publishSettlementCreatedEvent(List.of(1L, 2L));
        settlementEventPublisherAdapter.publishSettlementConfirmedEvent(List.of(3L));

        List<SettlementIndexEvent> published = events.stream(SettlementIndexEvent.class).toList();

        assertThat(published).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo(SettlementIndexEvent.IndexEventType.BATCH_CREATED);
            assertThat(e.getSettlementIds()).containsExactly(1L, 2L);
        });
        assertThat(published).anySatisfy(e -> {
            assertThat(e.getEventType()).isEqualTo(SettlementIndexEvent.IndexEventType.BATCH_CONFIRMED);
            assertThat(e.getSettlementIds()).containsExactly(3L);
        });
    }

    @Test
    @DisplayName("SettlementKafkaEventPublisherAdapter: SettlementCreated/SettlementConfirmed 가 outbox 에 적재된다")
    void settlementKafkaEventPublisherAdapter_savesToOutbox() {
        settlementKafkaEventPublisherAdapter.publishSettlementCreated(
                701L, 88L, new BigDecimal("9999.00"), LocalDate.of(2026, 7, 1), new BigDecimal("3000.00"));
        settlementKafkaEventPublisherAdapter.publishSettlementConfirmed(701L, 88L, new BigDecimal("9999.00"));

        Long createdCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Settlement' " +
                        "AND event_type = 'SettlementCreated'", Long.class);
        Long confirmedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = 'Settlement' " +
                        "AND event_type = 'SettlementConfirmed'", Long.class);
        assertThat(createdCount).isEqualTo(1L);
        assertThat(confirmedCount).isEqualTo(1L);

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'SettlementCreated'", String.class);
        assertThat(payload).contains("\"settlementId\"").contains("2026-07-01");
    }

    // ========== Ledger ==========

    @Test
    @DisplayName("LedgerPersistenceAdapter: save 후 findById/existsByReference/findByReference/findBySettlementDateBetween 로 조회된다")
    void ledgerPersistenceAdapter_save_and_query() {
        LocalDate settlementDate = LocalDate.of(2026, 5, 10);
        LedgerEntry entry = LedgerEntry.of(8001L, ReferenceType.SETTLEMENT, LedgerEntryType.SETTLEMENT_CONFIRMED,
                AccountType.ACCOUNTS_PAYABLE, AccountType.REVENUE, new BigDecimal("9700.00"),
                settlementDate, "정산 확정 분개");
        entry.post();

        LedgerEntry saved = ledgerPersistenceAdapter.save(entry);

        assertThat(saved.getId()).isNotNull();
        assertThat(ledgerPersistenceAdapter.findById(saved.getId())).isPresent();
        assertThat(ledgerPersistenceAdapter.existsByReference(8001L, ReferenceType.SETTLEMENT)).isTrue();
        assertThat(ledgerPersistenceAdapter.findByReference(8001L, ReferenceType.SETTLEMENT)).hasSize(1);
        assertThat(ledgerPersistenceAdapter.findBySettlementDateBetween(
                settlementDate.minusDays(1), settlementDate.plusDays(1))).hasSize(1);
        assertThat(ledgerPersistenceAdapter.existsByReference(8001L, ReferenceType.REFUND)).isFalse();
    }

    @Test
    @DisplayName("LedgerOutboxPersistenceAdapter: saveAll 후 findPending 에 나타나고 markDone 후 사라진다")
    void ledgerOutboxPersistenceAdapter_saveAll_findPending_markDone() {
        seedParentSettlement(8002L);
        ledgerOutboxPersistenceAdapter.saveAll(List.of(LedgerOutboxTask.create(8002L)));

        List<LedgerOutboxTask> pending = ledgerOutboxPersistenceAdapter.findPending(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).settlementId()).isEqualTo(8002L);

        ledgerOutboxPersistenceAdapter.markDone(pending.get(0).id());

        assertThat(ledgerOutboxPersistenceAdapter.findPending(10)).isEmpty();
    }

    @Test
    @DisplayName("LedgerOutboxPersistenceAdapter: markFailed 는 한도 미달이면 PENDING 유지, 한도 도달이면 FAILED 로 전환한다")
    void ledgerOutboxPersistenceAdapter_markFailed_retryVsFailed() {
        seedParentSettlement(8003L);
        ledgerOutboxPersistenceAdapter.saveAll(List.of(LedgerOutboxTask.create(8003L)));
        Long taskId = ledgerOutboxPersistenceAdapter.findPending(10).get(0).id();

        // 한도(maxRetry=5) 미달 — 재시도 카운트만 증가, PENDING 유지
        ledgerOutboxPersistenceAdapter.markFailed(taskId, "일시적 오류", 5);
        assertThat(ledgerOutboxPersistenceAdapter.findPending(10)).hasSize(1);

        // 한도(maxRetry=2) 도달 — FAILED 로 전환, findPending 에서 제외
        ledgerOutboxPersistenceAdapter.markFailed(taskId, "재시도 한도 초과", 2);
        assertThat(ledgerOutboxPersistenceAdapter.findPending(10)).isEmpty();
    }

    // ========== Settlement Batch Health / Loan Deduction / Index Queue ==========

    @Test
    @DisplayName("SettlementBatchHealthPersistenceAdapter: 날짜별 정산 대기/확정 건수 + 조정 대기 건수를 집계한다")
    void settlementBatchHealth_loadHealthSnapshot() {
        LocalDate date = LocalDate.of(2026, 6, 15);

        settlementRepository.save(newSettlement(70001L, date, "PENDING"));
        settlementRepository.save(newSettlement(70002L, date, "PENDING"));
        settlementRepository.save(newSettlement(70003L, date, "CONFIRMED"));

        SettlementAdjustmentJpaEntity adj = new SettlementAdjustmentJpaEntity();
        adj.setSettlementId(1L);
        adj.setAmount(new BigDecimal("100.00"));
        adj.setStatus("PENDING");
        adj.setAdjustmentDate(date);
        adjustmentRepository.save(adj);

        SettlementBatchHealthSnapshot snapshot = batchHealthAdapter.loadHealthSnapshot(date);

        assertThat(snapshot.getSettlementDate()).isEqualTo(date);
        assertThat(snapshot.getSettlementPendingCount()).isEqualTo(2);
        assertThat(snapshot.getSettlementConfirmedCount()).isEqualTo(1);
        assertThat(snapshot.getAdjustmentPendingCount()).isEqualTo(1);
        assertThat(snapshot.hasTooManyPendingSettlements()).isFalse();
        assertThat(snapshot.hasTooManyPendingAdjustments()).isFalse();
    }

    private SettlementJpaEntity newSettlement(Long paymentId, LocalDate date, String status) {
        SettlementJpaEntity s = new SettlementJpaEntity();
        s.setPaymentId(paymentId);
        s.setOrderId(paymentId + 1);
        s.setPaymentAmount(new BigDecimal("10000.00"));
        s.setCommission(new BigDecimal("300.00"));
        s.setNetAmount(new BigDecimal("9700.00"));
        s.setStatus(status);
        s.setSettlementDate(date);
        return s;
    }

    @Test
    @DisplayName("SettlementLoanDeductionPersistenceAdapter: record 후 findDeduction 조회, 재기록은 UPSERT 멱등이다")
    void settlementLoanDeduction_record_and_find() {
        seedParentSettlement(9999L);
        assertThat(loanDeductionAdapter.findDeduction(9999L)).isEmpty();

        loanDeductionAdapter.record(9999L, 55L, new BigDecimal("1500.00"));
        assertThat(loanDeductionAdapter.findDeduction(9999L)).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("1500.00"));

        // 같은 settlementId 로 재기록 — 멱등 UPSERT
        loanDeductionAdapter.record(9999L, 55L, new BigDecimal("2000.00"));
        assertThat(loanDeductionAdapter.findDeduction(9999L)).hasValueSatisfying(
                v -> assertThat(v).isEqualByComparingTo("2000.00"));
    }

    @Test
    @DisplayName("SettlementIndexQueueJpaEntity: 저장 시 기본값(status=PENDING, retryCount=0, maxRetries=3)과 nextRetryAt 이 설정된다")
    void settlementIndexQueueEntity_defaults() {
        seedParentSettlement(12345L);
        SettlementIndexQueueJpaEntity entity = new SettlementIndexQueueJpaEntity(12345L, "INDEX");
        LocalDateTime beforeSave = LocalDateTime.now();

        SettlementIndexQueueJpaEntity saved = indexQueueRepository.save(entity);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getSettlementId()).isEqualTo(12345L);
        assertThat(saved.getOperation()).isEqualTo("INDEX");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getMaxRetries()).isEqualTo(3);
        assertThat(saved.getNextRetryAt()).isAfter(beforeSave);

        SettlementIndexQueueJpaEntity reloaded = indexQueueRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getSettlementId()).isEqualTo(12345L);
    }
}
