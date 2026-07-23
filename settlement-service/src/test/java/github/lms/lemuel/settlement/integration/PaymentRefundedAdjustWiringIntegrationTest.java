package github.lms.lemuel.settlement.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.in.kafka.PaymentRefundedSettlementAdjustConsumer;
import github.lms.lemuel.settlement.adapter.out.persistence.SettlementJpaEntity;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementAdjustmentJpaRepository;
import github.lms.lemuel.settlement.adapter.out.persistence.SpringDataSettlementJpaRepository;
import github.lms.lemuel.settlement.application.port.in.AdjustSettlementForRefundUseCase;
import github.lms.lemuel.settlement.application.port.out.LoadSettlementPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 환불 이벤트 → 역정산 <b>배선 관통</b> 검증 (실 PostgreSQL Testcontainers).
 *
 * <p>과거 결함: {@code AdjustSettlementForRefundService} 는 구현·테스트 완비였지만 프로덕션
 * 호출처가 0건 — 환불 이벤트가 프로젝션 뷰만 갱신하고 settlements.net_amount 는 그대로여서
 * 환불 후에도 셀러에게 전액이 지급될 수 있었다. 이 테스트는 UseCase 를 직접 호출하지 않고
 * <b>실제 진입점(컨슈머 리스너)</b>에서 시작해 셀러 대면 결과(net_amount 감소)까지 관통해,
 * 배선 누락이 재발하면 red 가 나도록 한다.
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
class PaymentRefundedAdjustWiringIntegrationTest {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("settlement_test")
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

    @Autowired AdjustSettlementForRefundUseCase adjustUseCase;
    @Autowired LoadSettlementPort loadSettlementPort;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired SpringDataSettlementJpaRepository settlementRepo;
    @Autowired SpringDataSettlementAdjustmentJpaRepository adjustmentRepo;
    @Autowired TransactionTemplate tx;

    PaymentRefundedSettlementAdjustConsumer consumer;

    @BeforeEach
    void setUp() {
        // app.kafka.enabled=false 로 리스너 컨테이너는 뜨지 않으므로, 프로덕션과 동일한 의존
        // (실제 UseCase·포트 빈)으로 컨슈머를 조립해 리스너 메서드를 직접 구동한다.
        consumer = new PaymentRefundedSettlementAdjustConsumer(
                adjustUseCase, loadSettlementPort, processedEventRepository, new ObjectMapper(), null);
    }

    @Test
    @DisplayName("환불 이벤트 소비 시 settlements.net_amount 가 실제로 감소하고 조정 레코드가 남는다")
    void refundEvent_reducesNetAmount_andRecordsAdjustment() {
        // given — 결제 10,000 / 수수료 300 / net 9,700 인 PROCESSING 정산
        Long paymentId = 7101L;
        Long settlementId = tx.execute(s -> settlementRepo.save(
                newSettlement(paymentId, 8101L, "10000.00", "300.00", "9700.00", "PROCESSING")).getId());

        UUID eventId = UUID.randomUUID();
        ConsumerRecord<String, String> record = refundedRecord(eventId,
                "{\"paymentId\":" + paymentId + ",\"orderId\":8101,"
                        + "\"refundedAmount\":\"3000.00\",\"refundAmount\":\"3000.00\",\"refundId\":501}");

        // when — 프로덕션 리스너와 동일하게 트랜잭션 안에서 소비
        tx.executeWithoutResult(s -> consumer.onPaymentRefunded(record, mock(Acknowledgment.class)));

        // then — 셀러 대면 결과: netAmount = 10,000 − 3,000(환불) − 300(수수료) = 6,700
        SettlementJpaEntity after = settlementRepo.findById(settlementId).orElseThrow();
        assertThat(after.getRefundedAmount()).isEqualByComparingTo("3000.00");
        assertThat(after.getNetAmount()).isEqualByComparingTo("6700.00");

        // 감사 규약: settlement_adjustments 에 음수 조정 레코드
        assertThat(adjustmentRepo.findAll())
                .filteredOn(a -> settlementId.equals(a.getSettlementId()))
                .singleElement()
                .satisfies(a -> assertThat(a.getAmount()).isEqualByComparingTo("-3000.00"));
    }

    @Test
    @DisplayName("같은 event_id 재전송(리플레이)은 이중 차감 없이 스킵된다 — processed_events 멱등")
    void duplicateDelivery_doesNotDoubleAdjust() {
        Long paymentId = 7102L;
        Long settlementId = tx.execute(s -> settlementRepo.save(
                newSettlement(paymentId, 8102L, "10000.00", "300.00", "9700.00", "PROCESSING")).getId());

        UUID eventId = UUID.randomUUID();
        String payload = "{\"paymentId\":" + paymentId + ",\"orderId\":8102,"
                + "\"refundedAmount\":\"2000.00\",\"refundAmount\":\"2000.00\",\"refundId\":502}";

        tx.executeWithoutResult(s -> consumer.onPaymentRefunded(refundedRecord(eventId, payload), mock(Acknowledgment.class)));
        // 동일 event_id 로 재전송 (컨슈머 리밸런스·DLT replay 시나리오)
        tx.executeWithoutResult(s -> consumer.onPaymentRefunded(refundedRecord(eventId, payload), mock(Acknowledgment.class)));

        SettlementJpaEntity after = settlementRepo.findById(settlementId).orElseThrow();
        assertThat(after.getRefundedAmount())
                .as("멱등 방어 — 재전송이 이중 차감되면 4,000 이 된다")
                .isEqualByComparingTo("2000.00");
        assertThat(after.getNetAmount()).isEqualByComparingTo("7700.00");
    }

    private static ConsumerRecord<String, String> refundedRecord(UUID eventId, String json) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("lemuel.payment.refunded", 0, 0L, null, json);
        record.headers().add("event_id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        return record;
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
