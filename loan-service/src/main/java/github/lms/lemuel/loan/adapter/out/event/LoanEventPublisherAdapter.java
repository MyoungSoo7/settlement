package github.lms.lemuel.loan.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.application.port.out.PublishLoanEventPort;
import github.lms.lemuel.loan.domain.LoanAdvance;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * loan 이벤트를 Transactional Outbox 에 기록한다. 도메인 트랜잭션과 같은 트랜잭션에서 저장되어
 * 원자성이 보장되고, shared-common 의 OutboxPublisherScheduler 가 Kafka 로 비동기 발행한다.
 */
@Component
public class LoanEventPublisherAdapter implements PublishLoanEventPort {

    private static final String AGGREGATE_TYPE = "Loan";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public LoanEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort, ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishDisbursementRequested(LoanAdvance loan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loan.getId());
        payload.put("sellerId", loan.getSellerId());
        payload.put("amount", loan.getPrincipal()); // 셀러에게 선지급할 원금
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(loan.getId()),
                "LoanDisbursementRequested",
                toJson(payload)));
    }

    @Override
    public void publishRepaymentApplied(long settlementId, long sellerId, BigDecimal deducted) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("settlementId", settlementId);
        payload.put("sellerId", sellerId);
        payload.put("deducted", deducted);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(settlementId),
                "LoanRepaymentApplied",
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("loan 이벤트 직렬화 실패", e);
        }
    }
}
