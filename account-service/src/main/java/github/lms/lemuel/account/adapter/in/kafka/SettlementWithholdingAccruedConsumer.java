package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordAccountEntryUseCase;
import github.lms.lemuel.account.domain.AccountEntry;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * settlement.withholding_accrued → DR SELLER_PAYABLE / CR WITHHOLDING_PAYABLE 분개 적재
 * (ADR 0026 Option ① 확장, ADR 0027 §B 2026-07-24 정정 — 독립 GL 감사 HIGH #4 봉합).
 *
 * <p>정산 확정(payout 산정) 시점에 개인 셀러 원천징수가 실제 지급액에서 공제되면 settlement-service 가
 * 발행한다(account 는 소비 전용). 이 전기로 payoutCompleted 의 감액된 DR 이 남긴 SELLER_PAYABLE 잔여를
 * WITHHOLDING_PAYABLE(예수부채, 국세청 미납부분)로 재분류해 통제계정 폐루프를 유지한다.
 * 멱등: {@code processed_events} + {@code account_entries(source_topic, ref_type, ref_id)} UNIQUE(refId=settlementId).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementWithholdingAccruedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public SettlementWithholdingAccruedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                                ProcessedEventRepository processedEventRepository,
                                                ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.settlement-withholding-accrued}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onWithholdingAccrued(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "SettlementWithholdingAccrued"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String settlementId = requiredText(node, "settlementId", eventId);
        recordAccountEntryUseCase.record(AccountEntry.withholdingAccrued(
                requiredText(node, "sellerId", eventId),
                settlementId,
                requiredDecimal(node, "withholdingAmount", eventId)));
        log.info("원천징수 예수 반제 분개 적재. eventId={}, settlementId={}", eventId, settlementId);
    }
}
