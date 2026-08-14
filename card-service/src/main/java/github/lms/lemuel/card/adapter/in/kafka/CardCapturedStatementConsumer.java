package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.ChargeCardStatementUseCase;
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
 * 매입 확정 → 청구 명세서 반영 컨슈머.
 *
 * <p><b>왜 이 컨슈머가 생겼나</b>: 명세서를 열거나 청구액을 쌓는 경로가 시스템에 없었다.
 * {@code OpenCardStatementUseCase} 도 {@code CardStatement.addCharge} 도 호출자가 0 이라,
 * 마감(월 1회)·납부·연체 배치가 열린 적 없는 명세서를 찾으며 빈손으로 돌았다. 통합테스트가 명세서를
 * 직접 만들어 주고 있어 초록불이었고 그래서 가려져 있었다. 재발은
 * {@code CardArchitectureTest#모든_인바운드_포트는_어댑터에서_도달_가능하다} 가 구조로 막는다.
 *
 * <p><b>왜 매입 서비스에서 직접 부르지 않고 이벤트인가</b>: 승인·매입 경로를 청구가 오염시키지 않기
 * 위해서다 — 지출관리({@link CardCapturedExpenseConsumer})와 같은 원칙이고, 둘은 컨슈머 그룹이 달라
 * 서로의 실패에 영향을 주지 않는다.
 *
 * <h3>멱등</h3>
 * {@link IdempotentEventConsumer} 가 {@code processed_events(consumer_group, event_id)} L2 방어를 제공한다.
 * 같은 매입 이벤트를 두 번 받아도 청구액이 이중 계상되지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CardCapturedStatementConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card-statement";

    private final ChargeCardStatementUseCase chargeCardStatementUseCase;

    public CardCapturedStatementConsumer(ChargeCardStatementUseCase chargeCardStatementUseCase,
                                         ProcessedEventRepository processedEventRepository,
                                         ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.chargeCardStatementUseCase = chargeCardStatementUseCase;
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
        long cardAccountId = requiredLong(node, "cardAccountId", eventId);
        BigDecimal amount = new BigDecimal(requiredText(node, "amount", eventId));
        Instant capturedAt = Instant.parse(requiredText(node, "capturedAt", eventId));

        var statement = chargeCardStatementUseCase.charge(cardAccountId, capturedAt, amount);

        log.info("매입 확정 이벤트 소비 → 명세서 청구 반영. eventId={}, cardAccountId={}, amount={}, 명세서주기={}",
                eventId, cardAccountId, amount, statement.getBillingYearMonth());
    }
}
