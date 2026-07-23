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
 * payout.completed → DR SELLER_PAYABLE / CR CASH 분개 적재(ADR 0026 Option ① — 미지급금 상계 + 현금 유출).
 *
 * <p>셀러 정산금 실지급이 완료되면 settlement-service 가 발행한다(account 는 소비 전용). 이 전기로 정산
 * 생성 시 인식했던 SELLER_PAYABLE(즉시분) 또는 유보 해제로 재분류된 SELLER_PAYABLE 이 상계되고 플랫폼
 * CASH 가 유출돼 GL 현금 폐루프가 닫힌다.
 * 멱등: {@code processed_events} + {@code account_entries(source_topic, ref_type, ref_id)} UNIQUE(refId=payoutId).
 *
 * <p><b>MEDIUM-B(열린 질문 ④, 정책 미정)</b>: 수동 송금(payload.settlementId=null, 대응하는 settlement.created
 * 크레딧이 없는 payout)은 이 분개가 SELLER_PAYABLE 을 크레딧 없이 차변해 그 계정을 음수로 만들 수 있다.
 * 즉 "완전정산 셀러의 통제계정이 0으로 닫힌다"는 불변식이 수동 payout 대상 셀러에게는 성립하지 않을 수
 * 있다 — 별도 정책(예: 수동 payout 전용 채권 계정 신설, 또는 사전 조정분개 강제) 확정 전까지는 <b>의도적으로
 * 미봉합 상태로 둔다</b>. {@link github.lms.lemuel.account.domain.TrialBalance#normalBalanceRespected()} 와
 * {@link github.lms.lemuel.account.domain.AccountSummary#fullySettled()} 가 이 상태를 탐지하는 가드다(위반
 * 시 false) — 코드로 억지로 봉합하지 말고, 알람으로 다뤄라.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PayoutCompletedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordAccountEntryUseCase recordAccountEntryUseCase;

    public PayoutCompletedConsumer(RecordAccountEntryUseCase recordAccountEntryUseCase,
                                   ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordAccountEntryUseCase = recordAccountEntryUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.payout-completed}", groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onPayoutCompleted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() { return CONSUMER_GROUP; }

    @Override
    protected String eventType() { return "PayoutCompleted"; }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String payoutId = requiredText(node, "payoutId", eventId);
        AccountEntry entry = AccountEntry.payoutCompleted(
                requiredText(node, "sellerId", eventId),
                payoutId,
                requiredDecimal(node, "amount", eventId));
        recordAccountEntryUseCase.record(entry);
        log.info("셀러지급 분개 적재. eventId={}, payoutId={}", eventId, payoutId);
    }
}
