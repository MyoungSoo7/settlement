package github.lms.lemuel.investment.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.investment.application.port.out.PublishInvestmentEventPort;
import github.lms.lemuel.investment.domain.InvestmentOrder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 투자 이벤트를 Transactional Outbox 에 기록한다. 도메인 트랜잭션과 같은 트랜잭션에서 저장되어
 * 원자성이 보장되고, shared-common 의 OutboxPublisherScheduler 가 aggregateType="Investment"+eventType
 * 으로 라우팅해 토픽 {@code lemuel.investment.executed} 로 비동기 발행한다(loan 패턴 미러링).
 */
@Component
public class InvestmentEventPublisherAdapter implements PublishInvestmentEventPort {

    private static final String AGGREGATE_TYPE = "Investment";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public InvestmentEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort, ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishExecuted(InvestmentOrder order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.getId());
        payload.put("sellerId", order.getSellerId());
        payload.put("stockCode", order.getStockCode());
        payload.put("amount", order.getAmount());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(order.getId()),
                "InvestmentExecuted",
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("investment 이벤트 직렬화 실패", e);
        }
    }
}
