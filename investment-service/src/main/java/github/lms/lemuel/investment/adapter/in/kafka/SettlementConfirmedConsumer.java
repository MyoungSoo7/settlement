package github.lms.lemuel.investment.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase;
import github.lms.lemuel.investment.application.port.in.IngestConfirmedSettlementUseCase.IngestConfirmedSettlementCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * settlement 의 정산 확정 이벤트 수신 → 재원 프로젝션 적재 컨슈머.
 *
 * <p>멱등: {@link IdempotentEventConsumer} 의 processed_events(consumer_group, event_id) +
 * seller_funding_view.settlement_id UNIQUE(멱등 UPSERT). 여기서는 커맨드 매핑·use case 호출만 한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementConfirmedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-investment";

    private final IngestConfirmedSettlementUseCase ingestConfirmedSettlementUseCase;

    public SettlementConfirmedConsumer(IngestConfirmedSettlementUseCase ingestConfirmedSettlementUseCase,
                                       ProcessedEventRepository processedEventRepository,
                                       ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.ingestConfirmedSettlementUseCase = ingestConfirmedSettlementUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-confirmed}", groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onSettlementConfirmed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "SettlementConfirmed";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        IngestConfirmedSettlementCommand command = new IngestConfirmedSettlementCommand(
                requiredLong(node, "settlementId", eventId),
                requiredLong(node, "sellerId", eventId),
                requiredDecimal(node, "amount", eventId));

        ingestConfirmedSettlementUseCase.ingest(command);

        log.info("재원 프로젝션 적재 완료. eventId={}, settlementId={}", eventId, command.settlementId());
    }
}
