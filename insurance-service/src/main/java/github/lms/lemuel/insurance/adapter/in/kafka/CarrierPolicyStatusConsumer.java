package github.lms.lemuel.insurance.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.insurance.application.port.in.ReceiveCarrierPolicyStatusPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 외부 보험사(carrier) 계약 상태 통보 수신 컨슈머 — Idempotent 멱등 처리.
 *
 * <p>D1: 현재 no-op 구현체({@code NoOpCarrierPolicyStatusService})에 위임한다.
 * 훗날 보험사 연동이 구현되면 포트 구현체만 교체한다.
 *
 * <p><b>3단 멱등성 L2</b>: {@link IdempotentEventConsumer} 골격이 {@code processed_events}
 * 테이블에 {@code (consumer_group, event_id)} 복합 PK 로 중복 수신을 차단한다.
 * 같은 {@code event_id} 가 2회 전달되어도 도메인 부수효과({@link ReceiveCarrierPolicyStatusPort})
 * 는 정확히 1회만 실행된다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CarrierPolicyStatusConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-insurance";
    static final String EVENT_TYPE = "CarrierPolicyStatusReceived";

    private final ReceiveCarrierPolicyStatusPort receivePort;

    public CarrierPolicyStatusConsumer(ReceiveCarrierPolicyStatusPort receivePort,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper,
                                       ConsumedEventQuarantine quarantine) {
        super(processedEventRepository, objectMapper, quarantine);
        this.receivePort = receivePort;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.carrier-policy-status:lemuel.insurance.carrier_policy_status}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onCarrierPolicyStatus(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return EVENT_TYPE;
    }

    /**
     * 멱등 체크 통과 후 도메인 처리 위임.
     *
     * <p>필수 필드 누락 시 {@link IllegalArgumentException} → non-retryable → DLT.
     */
    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String policyNumber  = requiredText(node, "policyNumber",  eventId);
        String carrierStatus = requiredText(node, "carrierStatus", eventId);

        receivePort.onCarrierPolicyStatusReceived(policyNumber, carrierStatus);

        log.info("보험사 상태 통보 처리 완료. eventId={}, carrierStatus={}",
                eventId, carrierStatus);
    }
}
