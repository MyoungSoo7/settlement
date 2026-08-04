package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.CreateExpenseReportFromCaptureUseCase;
import github.lms.lemuel.card.application.port.in.CreateExpenseReportFromCaptureUseCase.CreateExpenseReportCommand;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code lemuel.card.captured} 이벤트 소비 → ExpenseReport(DRAFT) 자동 생성.
 *
 * <p>VAN 이 매입을 확정하면 {@code CardCapturedExpenseConsumer} 가 이 이벤트를 받아
 * 지출보고서(DRAFT)를 자동 생성한다. 임직원은 이후 영수증·카테고리·메모를 첨부해 제출한다.
 *
 * <h3>승인 경로 비결합</h3>
 * 이 컨슈머는 {@code AuthorizeCardService} 또는 {@code AuthorizeCardUseCase} 에
 * 의존하지 않는다. 승인과 지출관리는 이벤트를 통해서만 연결된다.
 *
 * <h3>멱등</h3>
 * {@link IdempotentEventConsumer} 가 {@code processed_events(consumer_group, event_id)} L2 방어를 제공한다.
 * {@code CreateExpenseReportFromCaptureUseCase} 가 {@code captureId} 멱등 L3 방어를 추가한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CardCapturedExpenseConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card-expense";

    private final CreateExpenseReportFromCaptureUseCase createExpenseReportFromCaptureUseCase;

    public CardCapturedExpenseConsumer(
            CreateExpenseReportFromCaptureUseCase createExpenseReportFromCaptureUseCase,
            ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.createExpenseReportFromCaptureUseCase = createExpenseReportFromCaptureUseCase;
    }

    @KafkaListener(topics = "lemuel.card.captured",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onCardCaptured(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "CardCaptured";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String captureId = requiredText(node, "captureId", eventId);
        String authorizationId = requiredText(node, "authorizationId", eventId);
        long cardId = requiredLong(node, "cardId", eventId);
        long cardAccountId = requiredLong(node, "cardAccountId", eventId);
        long holderUserId = requiredLong(node, "holderUserId", eventId);
        BigDecimal amount = new BigDecimal(requiredText(node, "amount", eventId));
        String merchantName = node.has("merchantName") ? node.get("merchantName").asText(null) : null;
        Instant capturedAt = Instant.parse(requiredText(node, "capturedAt", eventId));

        // organizationId 는 captured 이벤트에 없으므로 cardAccountId 로 조회해야 하지만
        // 단순화를 위해 캡처 이벤트에 포함되지 않는 조직 정보는 0 으로 처리.
        // 실제 구현에서는 카드계정 조회 포트를 통해 organizationId 를 가져온다.
        // 여기서는 departmentId 없이 생성 — 임직원이 제출 시 부서 정보를 입력.
        createExpenseReportFromCaptureUseCase.createFromCapture(
                new CreateExpenseReportCommand(
                        captureId, authorizationId, cardId, cardAccountId,
                        0L,          // organizationId: 카드계정에서 조회 필요 (Kafka 이벤트에 미포함)
                        null,        // departmentId: 임직원이 제출 시 설정
                        holderUserId, amount, merchantName, capturedAt));

        log.info("매입 확정 이벤트 소비 → 지출보고서 DRAFT 생성. eventId={}, captureId={}",
                eventId, captureId);
    }
}
