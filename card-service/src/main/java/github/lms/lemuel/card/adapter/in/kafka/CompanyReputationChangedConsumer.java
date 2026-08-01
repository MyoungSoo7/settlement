package github.lms.lemuel.card.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase;
import github.lms.lemuel.card.application.port.in.IngestReputationUseCase.ReputationCommand;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * company 평판 등급 변동 수신 → 셀러별 평판 프로젝션 upsert (loan 동형 소비).
 *
 * <p>한도 산정({@code CardLimitPolicy})의 haircut 입력이 여기서 낡으면
 * 평판 악화 법인에 과대 한도가 유지된다 — 일 1회 재산정(Task 13)이 이 프로젝션을 읽는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class CompanyReputationChangedConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-card";

    private final IngestReputationUseCase useCase;

    public CompanyReputationChangedConsumer(IngestReputationUseCase useCase,
                                            ProcessedEventRepository processedEventRepository,
                                            ObjectMapper objectMapper) {
        super(processedEventRepository, objectMapper);
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${app.kafka.topic.company-reputation-changed}",
            groupId = CONSUMER_GROUP, containerFactory = "kafkaListenerContainerFactory")
    @Transactional
    public void onReputationChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "CompanyReputationChanged";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        String grade = requiredText(node, "grade", eventId);
        JsonNode sellerIdsNode = required(node, "sellerIds", eventId);
        List<Long> sellerIds = new ArrayList<>();
        sellerIdsNode.forEach(n -> sellerIds.add(n.asLong()));

        useCase.ingest(new ReputationCommand(grade, sellerIds));
        log.info("평판 프로젝션 적재. eventId={}, grade={}, sellers={}", eventId, grade, sellerIds.size());
    }
}
