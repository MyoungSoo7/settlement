package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.application.port.in.ApplyLoanDeductionUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * loan-service 의 상환 차감 완료 이벤트 수신 → settlement 측 차감액 반영 (상환 saga 의 settlement 종착점).
 *
 * <p>정산건별 차감액을 보존해, 해당 정산 payout 시 순지급액(netAmount - deducted)으로 지급되게 한다.
 * 차감 0(대출 없는 셀러)도 정상 수신·기록된다.
 *
 * <p>멱등 골격은 {@link IdempotentEventConsumer} 가 소유하고(processed_events +
 * settlement_loan_deductions.settlement_id PK), 여기서는 차감액 반영만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class LoanRepaymentAppliedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final ApplyLoanDeductionUseCase applyLoanDeductionUseCase;

    public LoanRepaymentAppliedConsumer(ApplyLoanDeductionUseCase applyLoanDeductionUseCase,
                                        ProcessedEventRepository processedEventRepository,
                                        ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.applyLoanDeductionUseCase = applyLoanDeductionUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.loan-repayment-applied}", groupId = CONSUMER_GROUP)
    @Transactional
    public void onLoanRepaymentApplied(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "LoanRepaymentApplied";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        long settlementId = node.get("settlementId").asLong();
        long sellerId = node.get("sellerId").asLong();
        BigDecimal deducted = new BigDecimal(node.get("deducted").asText());

        applyLoanDeductionUseCase.apply(settlementId, sellerId, deducted);

        log.info("loan 차감 반영 완료. eventId={}, settlementId={}, deducted={}", eventId, settlementId, deducted);
    }
}
