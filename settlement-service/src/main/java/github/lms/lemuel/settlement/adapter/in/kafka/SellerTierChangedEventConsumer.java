package github.lms.lemuel.settlement.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ConsumedEventQuarantine;
import github.lms.lemuel.common.outbox.adapter.in.kafka.IdempotentEventConsumer;
import github.lms.lemuel.common.outbox.adapter.in.kafka.ProcessedEventRepository;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewJpaEntity;
import github.lms.lemuel.settlement.adapter.out.readmodel.SettlementUserViewRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * SellerTierChanged 이벤트 → settlement_user_view 등급 갱신 (ADR 0031 §4).
 *
 * <p><b>정산 계산에는 관여하지 않는다.</b> 정산 금액은 결제 시점 등급({@code PaymentCaptured} 동봉값)
 * 으로 이미 확정되며, 등급 변경은 이후 결제분부터 반영된다(비소급, ADR 0014). 이 컨슈머가 채우는
 * 값은 운영 조회·리포트에서 "이 셀러의 지금 등급"을 보여주기 위한 것이다 — 여기 값을 정산 계산에
 * 끌어다 쓰면 과거 정산이 조용히 재해석된다.
 *
 * <p>그래서 이 컨슈머는 늦거나 유실돼도 돈이 틀리지 않는다. 순서가 뒤집혀 옛 통지가 나중에 도착하면
 * 표시 등급이 잠시 과거로 보일 수 있으나, 다음 통지에서 수렴한다 — 이 대가를 감수하는 대신
 * 프로젝션에 버전 비교를 넣지 않는다(정산에 영향이 없으므로).
 *
 * <p>멱등 골격은 {@link IdempotentEventConsumer} 가 소유하고, 여기서는 뷰 매핑만 구현한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SellerTierChangedEventConsumer extends IdempotentEventConsumer {

    private static final String CONSUMER_GROUP = "lemuel-settlement";

    private final SettlementUserViewRepository userViewRepository;

    public SellerTierChangedEventConsumer(SettlementUserViewRepository userViewRepository,
                                          ProcessedEventRepository processedEventRepository,
                                          ObjectMapper objectMapper,
                                          ConsumedEventQuarantine quarantine) {
        super(processedEventRepository, objectMapper, quarantine);
        this.userViewRepository = userViewRepository;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.seller-tier-changed:lemuel.seller.tier_changed}",
            groupId = CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onSellerTierChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consume(record, ack);
    }

    @Override
    protected String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    protected String eventType() {
        return "SellerTierChanged";
    }

    @Override
    protected void handle(JsonNode node, UUID eventId) {
        Long sellerId = requiredLong(node, "sellerId", eventId);

        // upsert — 등급 통지가 UserRegistered 보다 먼저 올 수 있고(백필 순서), 리플레이에도 무해해야 한다.
        SettlementUserViewJpaEntity view = userViewRepository.findById(sellerId)
                .orElseGet(SettlementUserViewJpaEntity::new);
        view.setUserId(sellerId);
        // email 은 건드리지 않는다 — 이 이벤트가 소유하지 않는 필드다(UserRegistered 소유).
        view.setSellerTier(node.hasNonNull("newTier") ? node.get("newTier").asText() : null);
        view.setTierEffectiveFrom(node.hasNonNull("effectiveFrom")
                ? LocalDate.parse(node.get("effectiveFrom").asText()) : null);
        view.setUpdatedAt(OffsetDateTime.now());
        userViewRepository.save(view);

        log.info("settlement_user_view tier updated. eventId={}, sellerId={}, newTier={}",
                eventId, sellerId, view.getSellerTier());
    }
}
