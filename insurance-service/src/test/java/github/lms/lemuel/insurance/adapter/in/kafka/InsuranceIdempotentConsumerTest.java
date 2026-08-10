package github.lms.lemuel.insurance.adapter.in.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventJpaEntity;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.insurance.application.port.in.ReceiveCarrierPolicyStatusPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * CarrierPolicyStatusConsumer 의 멱등성 단위 테스트.
 *
 * <p>3단 멱등 방어 L2: {@code processed_events(consumer_group, event_id)} PK 체크가
 * {@link github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer} 골격 안에서
 * 강제된다는 것을 검증한다.
 *
 * <p>검증 항목:
 * <ol>
 *   <li>첫 번째 수신 → {@link ReceiveCarrierPolicyStatusPort} 1회 호출</li>
 *   <li>동일 event_id 재수신 → 포트 미호출 (exactly-once 도메인 부수효과)</li>
 *   <li>필수 필드 누락 payload → {@link IllegalArgumentException} + 포트 미호출</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class InsuranceIdempotentConsumerTest {

    @Mock
    ReceiveCarrierPolicyStatusPort receivePort;

    @Mock
    ProcessedEventRepository processedEventRepository;

    @Mock
    ConsumedEventQuarantine quarantine;

    CarrierPolicyStatusConsumer consumer;

    final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        consumer = new CarrierPolicyStatusConsumer(
                receivePort, processedEventRepository, objectMapper, quarantine);
    }

    // ── 테스트 헬퍼 ───────────────────────────────────────────────────────────

    private static ConsumerRecord<String, String> recordWith(String eventId, String json) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("lemuel.insurance.carrier_policy_status", 0, 0L, null, json);
        record.headers().add("event_id", eventId.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static ConsumerRecord<String, String> recordWithoutEventId(String json) {
        return new ConsumerRecord<>(
                "lemuel.insurance.carrier_policy_status", 0, 0L, null, json);
    }

    private static String validPayload(String policyNumber, String status) {
        return String.format(
                "{\"policyNumber\":\"%s\",\"carrierStatus\":\"%s\"}",
                policyNumber, status);
    }

    // ── 정상 흐름 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("새로운 event_id 수신 시 도메인 포트가 정확히 1회 호출된다")
    void firstDelivery_invokesPortOnce() {
        String eventId = UUID.randomUUID().toString();
        when(processedEventRepository.existsById(any())).thenReturn(false);

        consumer.onCarrierPolicyStatus(
                recordWith(eventId, validPayload("INS-2026-000001", "IN_FORCE")),
                mock(Acknowledgment.class));

        verify(receivePort, times(1))
                .onCarrierPolicyStatusReceived("INS-2026-000001", "IN_FORCE");
        verify(quarantine, never()).quarantine(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("멱등: 동일 event_id 재수신 시 도메인 포트가 호출되지 않는다")
    void duplicateEventId_portNotInvoked() {
        String eventId = UUID.randomUUID().toString();
        // 이미 처리된 것으로 간주
        when(processedEventRepository.existsById(any())).thenReturn(true);

        consumer.onCarrierPolicyStatus(
                recordWith(eventId, validPayload("INS-2026-000001", "LAPSED")),
                mock(Acknowledgment.class));

        verify(receivePort, never())
                .onCarrierPolicyStatusReceived(any(), any());
    }

    @Test
    @DisplayName("멱등: 첫 번째 수신 후 동일 event_id 재수신해도 포트는 총 1회만 호출된다")
    void deliveredTwice_portCalledExactlyOnce() {
        String eventId = UUID.randomUUID().toString();

        // 첫 수신: 미처리 → handle 호출
        when(processedEventRepository.existsById(any())).thenReturn(false);
        consumer.onCarrierPolicyStatus(
                recordWith(eventId, validPayload("INS-2026-000002", "IN_FORCE")),
                mock(Acknowledgment.class));

        // 두 번째 수신: 이미 처리됨 → skip
        when(processedEventRepository.existsById(any())).thenReturn(true);
        consumer.onCarrierPolicyStatus(
                recordWith(eventId, validPayload("INS-2026-000002", "IN_FORCE")),
                mock(Acknowledgment.class));

        // 포트는 정확히 1회만 호출됨
        verify(receivePort, times(1))
                .onCarrierPolicyStatusReceived("INS-2026-000002", "IN_FORCE");
    }

    // ── 처리 후 마커 저장 검증 ──────────────────────────────────────────────────

    @Test
    @DisplayName("정상 처리 후 processed_events 마커가 저장된다")
    void afterSuccessfulHandling_markerIsSaved() {
        when(processedEventRepository.existsById(any())).thenReturn(false);

        consumer.onCarrierPolicyStatus(
                recordWith(UUID.randomUUID().toString(),
                        validPayload("INS-2026-000003", "IN_FORCE")),
                mock(Acknowledgment.class));

        // save 가 1회 호출됨 = processed_events 에 멱등 마커 저장
        verify(processedEventRepository, times(1)).save(any(ProcessedEventJpaEntity.class));
    }

    @Test
    @DisplayName("비즈니스 처리가 실패하면 processed marker와 ack를 남기지 않는다")
    void businessFailure_doesNotSaveMarkerOrAck() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        doThrow(new IllegalStateException("business failure"))
                .when(receivePort).onCarrierPolicyStatusReceived(any(), any());
        Acknowledgment ack = mock(Acknowledgment.class);

        assertThatThrownBy(() -> consumer.onCarrierPolicyStatus(
                recordWith(UUID.randomUUID().toString(),
                        validPayload("INS-2026-FAIL", "IN_FORCE")),
                ack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("business failure");

        verify(processedEventRepository, never()).save(any());
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("처리 완료 로그에 평문 policyNumber를 남기지 않는다")
    void successfulHandlingLog_doesNotContainPlaintextPolicyNumber() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        Logger logger = (Logger) LoggerFactory.getLogger(CarrierPolicyStatusConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.onCarrierPolicyStatus(
                    recordWith(UUID.randomUUID().toString(),
                            validPayload("INS-SECRET-654321", "IN_FORCE")),
                    mock(Acknowledgment.class));

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("INS-SECRET-654321"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ── 오류 흐름 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("필수 필드(policyNumber) 누락 payload → IllegalArgumentException, 포트 미호출")
    void missingPolicyNumber_throwsAndPortNotInvoked() {
        when(processedEventRepository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() ->
                consumer.onCarrierPolicyStatus(
                        recordWith(UUID.randomUUID().toString(),
                                "{\"carrierStatus\":\"IN_FORCE\"}"),
                        mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policyNumber");

        verify(receivePort, never()).onCarrierPolicyStatusReceived(any(), any());
    }

    @Test
    @DisplayName("필수 필드(carrierStatus) 누락 payload → IllegalArgumentException, 포트 미호출")
    void missingCarrierStatus_throwsAndPortNotInvoked() {
        when(processedEventRepository.existsById(any())).thenReturn(false);

        assertThatThrownBy(() ->
                consumer.onCarrierPolicyStatus(
                        recordWith(UUID.randomUUID().toString(),
                                "{\"policyNumber\":\"INS-001\"}"),
                        mock(Acknowledgment.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carrierStatus");

        verify(receivePort, never()).onCarrierPolicyStatusReceived(any(), any());
    }

    @Test
    @DisplayName("event_id 누락 레코드는 durable quarantine 후 ack하고 비즈니스 처리를 하지 않는다")
    void missingEventId_isQuarantinedBeforeAck() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> record = recordWithoutEventId(
                validPayload("INS-2026-000004", "IN_FORCE"));

        consumer.onCarrierPolicyStatus(record, ack);

        verify(quarantine).quarantine(
                CarrierPolicyStatusConsumer.CONSUMER_GROUP,
                ConsumedEventQuarantine.Cause.MISSING_EVENT_ID,
                null,
                record,
                null);
        verify(ack).acknowledge();
        verify(receivePort, never()).onCarrierPolicyStatusReceived(any(), any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("불량 UUID event_id는 원문을 보존해 quarantine한다")
    void invalidEventId_isQuarantinedWithRawHeader() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> record = recordWith(
                "not-a-uuid", validPayload("INS-2026-000005", "IN_FORCE"));

        consumer.onCarrierPolicyStatus(record, ack);

        verify(quarantine).quarantine(
                CarrierPolicyStatusConsumer.CONSUMER_GROUP,
                ConsumedEventQuarantine.Cause.INVALID_EVENT_ID,
                "not-a-uuid",
                record,
                null);
        verify(ack).acknowledge();
        verify(receivePort, never()).onCarrierPolicyStatusReceived(any(), any());
    }

    @Test
    @DisplayName("quarantine 저장 실패는 전파되어 ack하지 않는다")
    void quarantineFailure_isPropagatedWithoutAck() {
        Acknowledgment ack = mock(Acknowledgment.class);
        ConsumerRecord<String, String> record = recordWithoutEventId(
                validPayload("INS-2026-000006", "IN_FORCE"));
        org.mockito.Mockito.doThrow(new IllegalStateException("quarantine unavailable"))
                .when(quarantine).quarantine(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> consumer.onCarrierPolicyStatus(record, ack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quarantine unavailable");

        verify(ack, never()).acknowledge();
        verify(receivePort, never()).onCarrierPolicyStatusReceived(any(), any());
    }

    // ── consumerGroup / eventType 고정값 검증 ────────────────────────────────

    @Test
    @DisplayName("consumerGroup() 이 'lemuel-insurance' 이다 — processed_events 멱등 키 구성")
    void consumerGroup_isLemuelInsurance() {
        assertThat(consumer.consumerGroup()).isEqualTo("lemuel-insurance");
    }

    @Test
    @DisplayName("eventType() 이 'CarrierPolicyStatusReceived' 이다 — processed_events 디버깅용")
    void eventType_isCarrierPolicyStatusReceived() {
        assertThat(consumer.eventType()).isEqualTo("CarrierPolicyStatusReceived");
    }
}
