package github.lms.lemuel.loan.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.application.port.out.PublishLeaseEventPort;
import github.lms.lemuel.loan.domain.LeaseContract;
import github.lms.lemuel.loan.domain.LeaseSchedule;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 리스·할부 이벤트를 Transactional Outbox 에 기록한다. 도메인 트랜잭션과 같은 트랜잭션에서 저장되어
 * 원자성이 보장되고, shared-common 의 OutboxPublisherScheduler 가 Kafka 로 비동기 발행한다.
 *
 * <p>토픽 라우팅: aggregateType="Loan" + eventType="LeaseActivated" →
 * {@code lemuel.loan.lease_activated} (KafkaOutboxPublisher.resolveTopic 의 camel→snake 규칙).
 *
 * <p><b>금액은 문자열</b>이다(DATA-STANDARD N5, loan 계열 통일) — 소비측에서 double 로 역직렬화되어
 * 원 단위가 흔들리는 것을 막는다. 계약 스키마가 이 표현을 고정한다(ADR 0024).
 */
@Component
public class LeaseEventPublisherAdapter implements PublishLeaseEventPort {

    private static final String AGGREGATE_TYPE = "Loan";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public LeaseEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                      @Qualifier("outboxObjectMapper") ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishActivated(LeaseContract contract) {
        LeaseSchedule schedule = contract.getSchedule();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractId", contract.getId());
        payload.put("borrowerUserId", contract.getBorrower().userId());
        payload.put("financeType", contract.getType().name());
        payload.put("assetDescription", contract.getAssetDescription());
        payload.put("financedAmount", schedule.financedAmount().toPlainString());
        payload.put("residualValue", schedule.residualValue().toPlainString());
        payload.put("monthlyRental", schedule.monthlyRental().toPlainString());
        payload.put("termMonths", schedule.termMonths());
        payload.put("activatedAt", String.valueOf(contract.getActivatedAt()));

        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(contract.getId()),
                "LeaseActivated",
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("리스 계약 이벤트 직렬화 실패", e);
        }
    }
}
