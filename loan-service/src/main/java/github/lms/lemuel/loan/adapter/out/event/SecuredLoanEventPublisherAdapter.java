package github.lms.lemuel.loan.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.loan.application.port.out.PublishSecuredLoanEventPort;
import github.lms.lemuel.loan.domain.SecuredLoan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 담보/개인신용 대출 이벤트를 Transactional Outbox 에 기록한다. 도메인 트랜잭션과 같은 트랜잭션에서
 * 저장되어 원자성이 보장되고, shared-common 의 OutboxPublisherScheduler 가 Kafka 로 비동기 발행한다.
 * 직접 {@code kafkaTemplate.send()} 호출은 원자성을 깨므로 금지된다.
 *
 * <p>토픽 라우팅: aggregateType="Loan" + eventType="SecuredLoanDisbursed" →
 * {@code lemuel.loan.secured_loan_disbursed} (KafkaOutboxPublisher.resolveTopic 의 camel→snake 규칙).
 * "SecuredLoanRepaid" → {@code lemuel.loan.secured_loan_repaid}.
 * "SecuredLoanPrincipalRepaid" → {@code lemuel.loan.secured_loan_principal_repaid}.
 *
 * <p>소비자는 account-service — 두 이벤트를 차주(BORROWER) 원장 {@code SECURED_LOAN_RECEIVABLE}
 * 분개로 소비한다(원금만). 완제 이벤트의 {@code prepaymentFee} 는 중도상환 완제에 부과된 수수료
 * 실액(회차 완제는 0)으로, GL 분개에는 쓰이지 않지만 계약 완결성을 위해 싣는다.
 */
@Component
public class SecuredLoanEventPublisherAdapter implements PublishSecuredLoanEventPort {

    private static final String AGGREGATE_TYPE = "Loan";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public SecuredLoanEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                            @Qualifier("outboxObjectMapper") ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishDisbursed(SecuredLoan loan) {
        Map<String, Object> payload = basePayload(loan);
        payload.put("principal", loan.getPrincipal());
        payload.put("annualRatePercent", loan.getAnnualRatePercent());
        payload.put("termMonths", loan.getTermMonths());
        save(loan, "SecuredLoanDisbursed", payload);
    }

    @Override
    public void publishRepaid(SecuredLoan loan, BigDecimal totalInterestPaid, BigDecimal prepaymentFee) {
        Map<String, Object> payload = basePayload(loan);
        payload.put("principal", loan.getPrincipal());
        payload.put("interestPaid", totalInterestPaid);
        payload.put("prepaymentFee", prepaymentFee);
        save(loan, "SecuredLoanRepaid", payload);
    }

    @Override
    public void publishPrincipalRepaid(SecuredLoan loan, BigDecimal principalRepaid, String reason) {
        Map<String, Object> payload = basePayload(loan);
        payload.put("principalRepaid", principalRepaid);
        payload.put("outstandingAfter", loan.getOutstanding());
        payload.put("reason", reason);
        save(loan, "SecuredLoanPrincipalRepaid", payload);
    }

    /** 두 이벤트가 공유하는 식별 필드 — 소비자가 상품·차주를 구분할 수 있어야 GL 매핑이 가능하다. */
    private static Map<String, Object> basePayload(SecuredLoan loan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("loanId", loan.getId());
        payload.put("productType", loan.getProductType().name());
        payload.put("borrowerUserId", loan.getBorrower().userId());
        payload.put("borrowerType", loan.getBorrower().type().name());
        return payload;
    }

    private void save(SecuredLoan loan, String eventType, Map<String, Object> payload) {
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(loan.getId()),
                eventType,
                toJson(eventType, payload)));
    }

    private String toJson(String eventType, Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("담보대출 이벤트 직렬화 실패: " + eventType, e);
        }
    }
}
