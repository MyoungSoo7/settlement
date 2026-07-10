package github.lms.lemuel.loan.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.application.port.out.PublishCorporateLoanEventPort;
import github.lms.lemuel.loan.domain.CorporateLoan;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 기업 신용대출 이벤트를 Transactional Outbox 에 기록한다. 도메인 트랜잭션과 같은 트랜잭션에서 저장되어
 * 원자성이 보장되고, shared-common 의 OutboxPublisherScheduler 가 Kafka 로 비동기 발행한다.
 *
 * <p>토픽 라우팅: aggregateType="Loan" + eventType="CorporateLoanDisbursed" →
 * {@code lemuel.loan.corporate_loan_disbursed} (KafkaOutboxPublisher.resolveTopic 의 camel→snake 규칙).
 */
@Component
public class CorporateLoanEventPublisherAdapter implements PublishCorporateLoanEventPort {

    private static final String AGGREGATE_TYPE = "Loan";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public CorporateLoanEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort, ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishDisbursed(CorporateLoan loan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loan.getId());
        payload.put("stockCode", loan.getStockCode());
        payload.put("corpName", loan.getCorpName());
        payload.put("principal", loan.getPrincipal());
        payload.put("fee", loan.getFee());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(loan.getId()),
                "CorporateLoanDisbursed",
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("기업대출 이벤트 직렬화 실패", e);
        }
    }
}
