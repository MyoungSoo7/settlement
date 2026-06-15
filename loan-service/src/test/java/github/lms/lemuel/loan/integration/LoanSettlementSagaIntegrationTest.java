package github.lms.lemuel.loan.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.LoanServiceApplication;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.out.persistence.SpringDataOutboxEventRepository;
import github.lms.lemuel.loan.adapter.in.kafka.SettlementConfirmedConsumer;
import github.lms.lemuel.loan.adapter.in.kafka.SettlementCreatedConsumer;
import github.lms.lemuel.loan.adapter.out.persistence.LoanAdvanceRepository;
import github.lms.lemuel.loan.adapter.out.persistence.LoanLedgerEntryRepository;
import github.lms.lemuel.loan.adapter.out.persistence.LoanRepaymentRepository;
import github.lms.lemuel.loan.adapter.out.persistence.SellerSettlementViewRepository;
import github.lms.lemuel.loan.application.port.in.ApplyRepaymentUseCase;
import github.lms.lemuel.loan.application.port.in.DisburseLoanUseCase;
import github.lms.lemuel.loan.application.port.in.IngestSettlementUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase;
import github.lms.lemuel.loan.application.port.in.RequestLoanUseCase.RequestLoanCommand;
import github.lms.lemuel.loan.domain.LoanAdvance;
import github.lms.lemuel.loan.domain.LoanStatus;
import github.lms.lemuel.loan.domain.SettlementViewStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 선정산 대출 종단 saga 통합 검증 — 실 PostgreSQL(Testcontainers) + 실 Flyway 마이그레이션(opslab 스키마).
 *
 * <p>컨슈머는 {@code @ConditionalOnProperty(app.kafka.enabled=true)} 라 브로커 없이 부팅하려고 kafka 를
 * 끈 채로 둔다. 대신 컨슈머를 실 빈들로 직접 조립해 SettlementCreated/Confirmed 레코드를 주입함으로써,
 * 카프카 인프라 없이 "이벤트 → 서비스 → 도메인 → 실 DB(뷰/대출/상환/원장/outbox)" 전 경로를 검증한다.
 */
@SpringBootTest(
        classes = LoanServiceApplication.class,
        properties = {
                "app.kafka.enabled=false",
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class LoanSettlementSagaIntegrationTest {

    private static final String CREATED_TOPIC = "lemuel.settlement.created";
    private static final String CONFIRMED_TOPIC = "lemuel.settlement.confirmed";

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("loan_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired IngestSettlementUseCase ingestSettlementUseCase;
    @Autowired ApplyRepaymentUseCase applyRepaymentUseCase;
    @Autowired RequestLoanUseCase requestLoanUseCase;
    @Autowired DisburseLoanUseCase disburseLoanUseCase;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired ObjectMapper objectMapper;

    @Autowired SellerSettlementViewRepository viewRepo;
    @Autowired LoanAdvanceRepository loanRepo;
    @Autowired LoanRepaymentRepository repaymentRepo;
    @Autowired LoanLedgerEntryRepository ledgerRepo;
    @Autowired SpringDataOutboxEventRepository outboxRepo;

    private SettlementCreatedConsumer createdConsumer;
    private SettlementConfirmedConsumer confirmedConsumer;

    @BeforeEach
    void setUp() {
        createdConsumer = new SettlementCreatedConsumer(ingestSettlementUseCase, processedEventRepository, objectMapper);
        confirmedConsumer = new SettlementConfirmedConsumer(applyRepaymentUseCase, processedEventRepository, objectMapper);
        repaymentRepo.deleteAll();
        ledgerRepo.deleteAll();
        loanRepo.deleteAll();
        viewRepo.deleteAll();
        outboxRepo.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("시나리오1: SettlementCreated 수신 → 로컬 정산뷰 PENDING 적재")
    void settlementCreated_materializesPendingView() {
        sendCreated(UUID.randomUUID(), 100L, 7L, "1000000", "2026-06-22");

        assertThat(viewRepo.findById(100L)).isPresent();
        assertThat(viewRepo.findById(100L).get().getStatus()).isEqualTo(SettlementViewStatus.PENDING);
        assertThat(viewRepo.findById(100L).get().getAmount()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("시나리오2: 정산생성→선지급(80만)→정산확정→상환차감(80만800), net=199,200 / 원장 3전표")
    void fullSaga_disburse_then_repayFromConfirmedSettlement() {
        // 정산 예정 100만 (담보)
        sendCreated(UUID.randomUUID(), 100L, 7L, "1000000", "2026-06-22");

        // 선지급 80만(LTV 80% 한도 충족), 5일 → 수수료 800 → 미상환 800,800
        LoanAdvance requested = requestLoanUseCase.request(
                new RequestLoanCommand(7L, new BigDecimal("800000"), 5));
        LoanAdvance disbursed = disburseLoanUseCase.disburse(requested.getId());
        assertThat(disbursed.getStatus()).isEqualTo(LoanStatus.DISBURSED);
        assertThat(disbursed.getOutstanding()).isEqualByComparingTo("800800");
        assertThat(countOutbox("LoanDisbursementRequested")).isEqualTo(1);

        // 정산 확정 100만 → 상환 차감
        sendConfirmed(UUID.randomUUID(), 100L, 7L, "1000000");

        // 대출 전액 상환
        LoanAdvance after = loanRepo.findById(disbursed.getId())
                .map(e -> LoanAdvance.reconstitute(e.getId(), e.getSellerId(), e.getPrincipal(),
                        e.getFee(), e.getOutstanding(), e.getStatus()))
                .orElseThrow();
        assertThat(after.getStatus()).isEqualTo(LoanStatus.REPAID);
        assertThat(after.getOutstanding()).isEqualByComparingTo("0");

        // 상환 기록 = 차감 800,800
        assertThat(repaymentRepo.existsBySettlementId(100L)).isTrue();
        assertThat(repaymentRepo.findAll().getFirst().getDeducted()).isEqualByComparingTo("800800");

        // 뷰 CONFIRMED 전이
        assertThat(viewRepo.findById(100L).get().getStatus()).isEqualTo(SettlementViewStatus.CONFIRMED);

        // 발행: LoanRepaymentApplied (settlement 가 net=1,000,000-800,800=199,200 지급)
        assertThat(countOutbox("LoanRepaymentApplied")).isEqualTo(1);

        // 복식부기 3전표: 선지급 + 수수료 + 상환
        assertThat(ledgerRepo.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("시나리오3: 대출 없는 셀러 → 차감 0 기록·발행(전액 지급)")
    void noLoanSeller_deductsZero() {
        sendConfirmed(UUID.randomUUID(), 200L, 9L, "500000");

        assertThat(repaymentRepo.existsBySettlementId(200L)).isTrue();
        assertThat(repaymentRepo.findAll().getFirst().getDeducted()).isEqualByComparingTo("0");
        assertThat(countOutbox("LoanRepaymentApplied")).isEqualTo(1);
    }

    @Test
    @DisplayName("시나리오4: 동일 event_id 의 SettlementConfirmed 중복 수신 → 차감 1회(멱등)")
    void duplicateConfirmed_idempotent() {
        sendCreated(UUID.randomUUID(), 300L, 7L, "1000000", "2026-06-22");
        LoanAdvance requested = requestLoanUseCase.request(
                new RequestLoanCommand(7L, new BigDecimal("800000"), 5));
        disburseLoanUseCase.disburse(requested.getId());

        UUID eventId = UUID.randomUUID();
        sendConfirmed(eventId, 300L, 7L, "1000000");
        sendConfirmed(eventId, 300L, 7L, "1000000"); // 재수신

        assertThat(repaymentRepo.findAll()).hasSize(1);
        assertThat(countOutbox("LoanRepaymentApplied")).isEqualTo(1);
    }

    // ---- helpers ----

    private void sendCreated(UUID eventId, long settlementId, long sellerId, String amount, String dueDate) {
        String payload = "{\"settlementId\":" + settlementId + ",\"sellerId\":" + sellerId
                + ",\"amount\":\"" + amount + "\",\"dueDate\":\"" + dueDate + "\"}";
        createdConsumer.onSettlementCreated(record(CREATED_TOPIC, eventId, payload, String.valueOf(settlementId)),
                mock(Acknowledgment.class));
    }

    private void sendConfirmed(UUID eventId, long settlementId, long sellerId, String amount) {
        String payload = "{\"settlementId\":" + settlementId + ",\"sellerId\":" + sellerId
                + ",\"amount\":\"" + amount + "\"}";
        confirmedConsumer.onSettlementConfirmed(record(CONFIRMED_TOPIC, eventId, payload, String.valueOf(settlementId)),
                mock(Acknowledgment.class));
    }

    private ConsumerRecord<String, String> record(String topic, UUID eventId, String payload, String key) {
        var r = new ConsumerRecord<>(topic, 0, 0L, key, payload);
        r.headers().add(new RecordHeader("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    private long countOutbox(String eventType) {
        return outboxRepo.findAll().stream()
                .map(OutboxEventJpaEntity::getEventType)
                .filter(eventType::equals)
                .count();
    }
}
