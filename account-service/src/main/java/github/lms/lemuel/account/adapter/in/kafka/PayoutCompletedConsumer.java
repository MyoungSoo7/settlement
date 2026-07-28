package github.lms.lemuel.account.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.account.application.port.in.RecordPayoutUseCase;
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
 * <p><b>감사 MED-3 봉합(채권 라우팅)</b>: 대응하는 SELLER_PAYABLE 크레딧이 없는 실지급(예: 수동 송금)은
 * 단순 상계 전기만으로는 SELLER_PAYABLE 을 음수로 몰아 "완전정산 통제계정 0" 불변식을 깬다. 이를 막기 위해
 * 컨슈머는 파싱만 하고 전기를 {@link RecordPayoutUseCase}에 위임한다 — 서비스가 현재 SELLER_PAYABLE 잔액을
 * 기준으로 payout 차변을 분할해, 잔액 이내분은 {@code payoutCompleted}(DR SELLER_PAYABLE / CR CASH), 초과분은
 * {@code payoutAdvanceReceivable}(DR SELLER_RECOVERY_RECEIVABLE / CR CASH)로 라우팅한다(음수 없이 CASH 유출
 * 총액 정확 기록). {@link github.lms.lemuel.account.domain.TrialBalance#normalBalanceRespected()} 와
 * {@link github.lms.lemuel.account.domain.AccountSummary#fullySettled()} 가 여전히 사후 가드로 남는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class PayoutCompletedConsumer extends IdempotentEventConsumer {

    static final String CONSUMER_GROUP = "lemuel-account";

    private final RecordPayoutUseCase recordPayoutUseCase;

    public PayoutCompletedConsumer(RecordPayoutUseCase recordPayoutUseCase,
                                   ProcessedEventRepository processedEventRepository,
                                   ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.recordPayoutUseCase = recordPayoutUseCase;
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
        recordPayoutUseCase.recordPayout(
                requiredText(node, "sellerId", eventId),
                payoutId,
                requiredDecimal(node, "amount", eventId));
        log.info("셀러지급 분개 적재(채권 라우팅). eventId={}, payoutId={}", eventId, payoutId);
    }
}
