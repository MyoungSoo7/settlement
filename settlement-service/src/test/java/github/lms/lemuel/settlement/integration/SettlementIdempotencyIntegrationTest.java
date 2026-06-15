package github.lms.lemuel.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.in.kafka.PaymentEventKafkaConsumer;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.in.CreateSettlementFromPaymentUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSellerSettlementCyclePort;
import github.lms.lemuel.settlement.application.port.out.LoadSellerTierPort;
import github.lms.lemuel.settlement.domain.SellerTier;
import github.lms.lemuel.settlement.domain.SettlementCycle;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 정산 멱등성 3단 방어 통합 검증 — 실 PostgreSQL(Testcontainers) 기반.
 *
 * <p>"결제 이벤트가 중복 수신되어도 정산은 정확히 1건만 생성된다"(중복 정산 0건)를
 * 각 방어 계층별로 end-to-end 로 입증한다. CLAUDE.md / {@link PaymentEventKafkaConsumer}
 * 주석이 선언한 3단 방어와 1:1 대응한다.
 *
 * <ol>
 *   <li><b>2계층 — 컨슈머 재수신 방지</b>: 동일 {@code event_id} 가 두 번 도착하면
 *       {@code processed_events(consumer_group, event_id)} 멱등 체크로 두 번째는 스킵된다.
 *       → 정산 생성 UseCase 가 한 번만 호출되고 정산은 1건.</li>
 *   <li><b>3계층 — 애플리케이션 멱등</b>: 같은 결제(paymentId)가 <em>다른</em> {@code event_id} 로
 *       재유입돼 2계층을 통과하더라도, {@code findByPaymentId} 가 기존 정산을 반환해
 *       새 정산을 저장하지 않는다. → 여전히 1건.</li>
 *   <li><b>스키마 계층 — DB UNIQUE</b>: 애플리케이션 가드를 모두 우회해 같은 paymentId 로
 *       정산을 직접 2건 저장하려 하면 {@code uk_settlements_payment_id} 제약이 최종 차단한다.</li>
 * </ol>
 *
 * <p>컨슈머는 {@code @ConditionalOnProperty(app.kafka.enabled=true)} 라 브로커 없이 부팅하려고
 * kafka 를 끈 상태로 둔다. 대신 컨슈머를 실제 빈들로 직접 조립해 {@code onPaymentCaptured} 를
 * 호출함으로써, 카프카 인프라 없이 멱등 로직만 실 DB 에 대해 검증한다.
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
@Import(SettlementIdempotencyIntegrationTest.StubSellerMetaConfig.class)
class SettlementIdempotencyIntegrationTest {

    /**
     * 셀러 등급/주기 조회 stub — 멱등성 검증과 무관한 의존성만 분리.
     *
     * <p>실 어댑터({@code SellerTierJdbcAdapter})는 {@code opslab.payments→orders→products→users}
     * 를 native SQL 로 조인하는데, settlement-service read-model 엔티티는 그 컬럼 전부를 매핑하지
     * 않으므로(예: users 는 email 만) create-drop 스키마에선 해당 쿼리가 성립하지 않는다. 등급 해석은
     * {@code CreateSettlementFromPaymentServiceTest} 가 단위로 검증하므로, 여기서는 empty(=NORMAL
     * fallback)로 고정해 정산 생성·멱등 경로만 실 DB 로 검증한다. @Primary 로 실 어댑터보다 우선 주입.
     */
    @TestConfiguration
    static class StubSellerMetaConfig {
        @Bean
        @Primary
        SellerMetaStub sellerMetaStub() {
            return new SellerMetaStub();
        }

        static class SellerMetaStub implements LoadSellerTierPort, LoadSellerSettlementCyclePort {
            @Override public Optional<SellerTier> findTierByPaymentId(Long paymentId) { return Optional.empty(); }
            @Override public Optional<SettlementCycle> findCycleByPaymentId(Long paymentId) { return Optional.empty(); }
        }
    }

    private static final String TOPIC = "lemuel.payment.captured";

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opslab_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("POSTGRES_USER", POSTGRES::getUsername);
        r.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired CreateSettlementFromPaymentUseCase createSettlementFromPaymentUseCase;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired ObjectMapper objectMapper;

    private PaymentEventKafkaConsumer consumer;

    @BeforeEach
    void setUp() {
        // 컨슈머는 kafka.enabled=false 라 빈으로 안 뜸 → 실 빈들로 직접 조립.
        consumer = new PaymentEventKafkaConsumer(
                createSettlementFromPaymentUseCase, processedEventRepository, objectMapper);
        // create-drop 스키마는 컨텍스트 단위라 메서드 간 데이터가 누적된다 — 매 테스트 전 비운다.
        settlementRepo.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    @DisplayName("2계층: 동일 event_id 가 두 번 수신돼도 정산은 1건만 생성된다 (processed_events 멱등)")
    void duplicateSameEventId_createsSettlementOnce() {
        long paymentId = 7101L;
        long orderId = 8101L;
        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        var record = paymentCapturedRecord(eventId, paymentId, orderId, "50000");

        // 첫 수신 — 정산 생성 + processed_events 기록
        var ack1 = mock(org.springframework.kafka.support.Acknowledgment.class);
        consumer.onPaymentCaptured(record, ack1);
        verify(ack1).acknowledge();

        // 동일 event_id 재수신 (브로커 at-least-once 재전송 시뮬레이션)
        var ack2 = mock(org.springframework.kafka.support.Acknowledgment.class);
        consumer.onPaymentCaptured(paymentCapturedRecord(eventId, paymentId, orderId, "50000"), ack2);
        verify(ack2).acknowledge();

        assertThat(countSettlements(paymentId))
                .as("동일 event_id 2회 → processed_events 가 2번째를 스킵 → 정산 1건")
                .isEqualTo(1);
        assertThat(processedEventRepository.count())
                .as("처리 이력도 (group,event_id) 단일 키로 1건")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("3계층: 같은 paymentId 가 다른 event_id 로 재유입돼도 정산은 1건만 생성된다 (findByPaymentId 멱등)")
    void samePaymentDifferentEventId_createsSettlementOnce() {
        long paymentId = 7202L;
        long orderId = 8202L;
        UUID eventA = UUID.fromString("22222222-2222-2222-2222-2222222222aa");
        UUID eventB = UUID.fromString("22222222-2222-2222-2222-2222222222bb");

        // 서로 다른 event_id 두 개가 같은 결제를 운반 (outbox 중복 발행/스키마 변경 등 비정상 경로 가정)
        consumer.onPaymentCaptured(paymentCapturedRecord(eventA, paymentId, orderId, "30000"),
                mock(org.springframework.kafka.support.Acknowledgment.class));
        consumer.onPaymentCaptured(paymentCapturedRecord(eventB, paymentId, orderId, "30000"),
                mock(org.springframework.kafka.support.Acknowledgment.class));

        assertThat(countSettlements(paymentId))
                .as("event_id 가 달라 2계층은 통과하지만, 애플리케이션 멱등(findByPaymentId)이 정산을 1건으로 유지")
                .isEqualTo(1);
        assertThat(processedEventRepository.count())
                .as("두 이벤트 모두 처리 기록 — 멱등은 '정산 1건'이지 '이벤트 무시'가 아님")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("스키마 계층: 같은 payment_id 로 정산 2건 저장 시 DB UNIQUE 제약(uk_settlements_payment_id)이 차단한다")
    void duplicatePaymentId_violatesUniqueConstraint() {
        long paymentId = 7303L;

        settlementRepo.saveAndFlush(newSettlement(paymentId, 8303L, "10000.00", "300.00", "9700.00"));

        SettlementJpaEntity duplicate = newSettlement(paymentId, 8304L, "10000.00", "300.00", "9700.00");
        assertThatThrownBy(() -> settlementRepo.saveAndFlush(duplicate))
                .as("애플리케이션 가드를 우회해도 스키마 UNIQUE 가 최종 방어선")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- helpers ----

    private long countSettlements(long paymentId) {
        return settlementRepo.findAll().stream()
                .filter(s -> s.getPaymentId().equals(paymentId))
                .count();
    }

    private ConsumerRecord<String, String> paymentCapturedRecord(UUID eventId, long paymentId,
                                                                 long orderId, String amount) {
        String payload = "{\"paymentId\":" + paymentId + ",\"orderId\":" + orderId
                + ",\"amount\":\"" + amount + "\"}";
        var record = new ConsumerRecord<>(TOPIC, 0, 0L, String.valueOf(paymentId), payload);
        record.headers().add(new RecordHeader("event_id",
                eventId.toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private SettlementJpaEntity newSettlement(long paymentId, long orderId,
                                              String paymentAmount, String commission, String netAmount) {
        SettlementJpaEntity s = new SettlementJpaEntity();
        s.setPaymentId(paymentId);
        s.setOrderId(orderId);
        s.setPaymentAmount(new BigDecimal(paymentAmount));
        s.setCommission(new BigDecimal(commission));
        s.setNetAmount(new BigDecimal(netAmount));
        s.setStatus("REQUESTED");
        s.setSettlementDate(LocalDate.now());
        return s;
    }
}
