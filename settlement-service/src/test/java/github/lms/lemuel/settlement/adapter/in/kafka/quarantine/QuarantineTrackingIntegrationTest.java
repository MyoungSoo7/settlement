package github.lms.lemuel.settlement.adapter.in.kafka.quarantine;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.SettlementServiceApplication;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.in.kafka.SettlementProjectionMetrics;
import github.lms.lemuel.settlement.adapter.in.kafka.UserRegisteredEventConsumer;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0-3 이벤트 격리 완결 통합 증명 (seed-p0-3 AC1~AC3).
 *
 * <p>격리 기록이 (1) 리스너 트랜잭션 롤백에도 살아남고(REQUIRES_NEW), (2) 같은 불량 레코드의
 * 재전달에 멱등이며, (3) 격리 이벤트를 수정 후 재처리하면 processed_events 멱등과 합류해
 * 도메인 처리가 정확히 한 번만 적용됨을 실제 PostgreSQL 로 증명한다.
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
class QuarantineTrackingIntegrationTest {

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

    @Autowired ConsumedEventQuarantine quarantine;
    @Autowired QuarantinedEventRepository quarantinedEventRepository;
    @Autowired DuplicateEventRepository duplicateEventRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired SettlementUserViewRepository userViewRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void clean() {
        quarantinedEventRepository.deleteAll();
        duplicateEventRepository.deleteAll();
        processedEventRepository.deleteAll();
        userViewRepository.deleteAll();
    }

    private static ConsumerRecord<String, String> record(String topic, long offset, String payload, String eventIdHeader) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(topic, 0, offset, "key", payload);
        if (eventIdHeader != null) {
            r.headers().add(new RecordHeader("event_id", eventIdHeader.getBytes(StandardCharsets.UTF_8)));
        }
        return r;
    }

    @Test
    @DisplayName("격리 기록은 둘러싼 트랜잭션이 롤백돼도 살아남는다 (REQUIRES_NEW)")
    void quarantineRecordSurvivesEnclosingRollback() {
        transactionTemplate.executeWithoutResult(status -> {
            quarantine.quarantine("g1", ConsumedEventQuarantine.Cause.MISSING_EVENT_ID, null,
                    record("t.roll", 1L, "{\"a\":1}", null), null);
            status.setRollbackOnly();
        });

        assertThat(quarantinedEventRepository.count()).isEqualTo(1);
        QuarantinedEventJpaEntity saved = quarantinedEventRepository.findAll().getFirst();
        assertThat(saved.getStatus()).isEqualTo(QuarantinedEventJpaEntity.Status.NEW);
        assertThat(saved.getPayload()).isEqualTo("{\"a\":1}");
        assertThat(saved.getCause()).isEqualTo(ConsumedEventQuarantine.Cause.MISSING_EVENT_ID);
    }

    @Test
    @DisplayName("같은 (group, topic, partition, offset) 재전달은 격리 행을 중복 생성하지 않는다")
    void quarantineIsIdempotentPerRecordCoordinate() {
        ConsumerRecord<String, String> r = record("t.dup", 7L, "bad", null);
        quarantine.quarantine("g1", ConsumedEventQuarantine.Cause.MISSING_EVENT_ID, null, r, null);
        quarantine.quarantine("g1", ConsumedEventQuarantine.Cause.MISSING_EVENT_ID, null, r, null);

        assertThat(quarantinedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("격리 → 수정 재처리 → 재전달: 도메인 처리는 정확히 한 번 (processed_events 멱등 합류)")
    void quarantinedEventReplayAppliesExactlyOnce() {
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(
                userViewRepository, processedEventRepository, objectMapper,
                new SettlementProjectionMetrics(new SimpleMeterRegistry()), quarantine);
        UUID eventId = UUID.randomUUID();
        String topic = "lemuel.user.registered";

        // 1) 계약 위반 payload(userId 누락) → IAE(DLT 공존) + 격리 기록, ack 없음, 도메인 효과 0
        Acknowledgment badAck = mock(Acknowledgment.class);
        assertThatThrownBy(() -> consumer.onUserRegistered(
                record(topic, 100L, "{\"email\":\"a@b.c\"}", eventId.toString()), badAck))
                .isInstanceOf(IllegalArgumentException.class);
        verify(badAck, never()).acknowledge();
        assertThat(quarantinedEventRepository.count()).isEqualTo(1);
        assertThat(quarantinedEventRepository.findAll().getFirst().getCause())
                .isEqualTo(ConsumedEventQuarantine.Cause.INVALID_PAYLOAD);
        assertThat(userViewRepository.count()).isZero();

        // 2) 수정된 payload 로 재처리(운영자 replay 를 소비 경로로 재현) → 정확히 1회 적용
        Acknowledgment fixedAck = mock(Acknowledgment.class);
        consumer.onUserRegistered(record(topic, 101L, "{\"userId\":77,\"email\":\"a@b.c\"}", eventId.toString()), fixedAck);
        verify(fixedAck).acknowledge();
        assertThat(userViewRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);

        // 3) 같은 event_id 재전달(at-least-once) → 중복 추적만 남고 도메인 효과는 그대로 1회
        Acknowledgment dupAck = mock(Acknowledgment.class);
        consumer.onUserRegistered(record(topic, 102L, "{\"userId\":77,\"email\":\"a@b.c\"}", eventId.toString()), dupAck);
        verify(dupAck).acknowledge();
        assertThat(userViewRepository.count()).isEqualTo(1);
        assertThat(duplicateEventRepository.totalHits()).isEqualTo(1);
    }
}
