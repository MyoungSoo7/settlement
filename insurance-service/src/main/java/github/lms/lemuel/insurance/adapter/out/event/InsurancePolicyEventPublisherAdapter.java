package github.lms.lemuel.insurance.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.insurance.application.port.out.PublishInsuranceEventPort;
import github.lms.lemuel.insurance.domain.CommissionSchedule;
import github.lms.lemuel.insurance.domain.Policy;
import github.lms.lemuel.insurance.domain.PolicyStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 보험 도메인 이벤트를 Transactional Outbox 에 기록한다.
 *
 * <p>도메인 트랜잭션과 같은 트랜잭션에서 저장되어 원자성이 보장되고,
 * shared-common {@code OutboxPublisherScheduler} 가 aggregateType="Insurance" + eventType 으로
 * 라우팅해 아래 토픽으로 발행한다:
 * <ul>
 *   <li>{@code lemuel.insurance.policy_issued}</li>
 *   <li>{@code lemuel.insurance.policy_status_changed}</li>
 *   <li>{@code lemuel.insurance.commission_confirmed}</li>
 * </ul>
 *
 * <p><b>파티션 키(aggregateId)</b>: policyNumber — 한 계약의 발행·상태변경·수수료 이벤트가
 * 같은 파티션에 순서대로 떨어져야 loan/investment 소비자가 일관된 신용보강 상태를 본다.
 *
 * <p><b>금액</b>: {@link BigDecimal#toPlainString()} 문자열(DATA-STANDARD N5).
 * {@code @Qualifier("outboxObjectMapper")} 가 BigDecimal 을 자동으로 plain-string 으로 직렬화한다.
 */
@Component
public class InsurancePolicyEventPublisherAdapter implements PublishInsuranceEventPort {

    /** KafkaOutboxPublisher.resolveTopic 이 "Insurance" + "InsurancePolicyIssued" → lemuel.insurance.policy_issued 로 변환. */
    static final String AGGREGATE_TYPE = "Insurance";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public InsurancePolicyEventPublisherAdapter(
            SaveOutboxEventPort saveOutboxEventPort,
            @Qualifier("outboxObjectMapper") ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    /**
     * 계약 발행 — 토픽 {@code lemuel.insurance.policy_issued}.
     *
     * <p>보장금액({@code coverageAmount})을 포함해 loan/investment 가 신용보강 관점에서 소비한다.
     */
    @Override
    public void publishPolicyIssued(Policy policy, BigDecimal coverageAmount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber",    policy.getPolicyNumber());
        payload.put("fcId",            policy.getFcId());
        payload.put("status",          policy.getStatus().name());
        payload.put("effectiveDate",   policy.getEffectiveDate().toString());
        if (policy.getMaturityDate() != null) {
            payload.put("maturityDate", policy.getMaturityDate().toString());
        }
        payload.put("premiumAmount",   policy.getPremiumAmount().toPlainString());
        payload.put("coverageAmount",  coverageAmount.toPlainString());

        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                policy.getPolicyNumber(),  // 파티션 키
                "InsurancePolicyIssued",   // → lemuel.insurance.policy_issued
                toJson(payload)));
    }

    /**
     * 계약 상태 변경 — 토픽 {@code lemuel.insurance.policy_status_changed}.
     *
     * <p>D7 허용 전이 7개가 모두 이 이벤트를 통해 외부에 알려진다.
     */
    @Override
    public void publishPolicyStatusChanged(Policy policy, PolicyStatus previousStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber",    policy.getPolicyNumber());
        payload.put("fcId",            policy.getFcId());
        payload.put("previousStatus",  previousStatus.name());
        payload.put("newStatus",       policy.getStatus().name());
        if (policy.getLapsedAt() != null) {
            payload.put("lapsedAt", policy.getLapsedAt().toString());
        }

        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                policy.getPolicyNumber(),          // 파티션 키
                "InsurancePolicyStatusChanged",    // → lemuel.insurance.policy_status_changed
                toJson(payload)));
    }

    /**
     * 수수료 확정 — 토픽 {@code lemuel.insurance.commission_confirmed}.
     *
     * <p>D4: 초년도 선지급 스케줄 12개 행이 확정됨을 settlement-service/account-service 에 알린다.
     * 금액은 DATA-STANDARD N5(plain string).
     */
    @Override
    public void publishCommissionConfirmed(String policyNumber, List<CommissionSchedule> schedules) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policyNumber",      policyNumber);

        List<Map<String, Object>> scheduleDtos = new ArrayList<>(schedules.size());
        for (CommissionSchedule s : schedules) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("commissionId",      s.getCommissionId());
            dto.put("fcId",              s.getFcId());
            dto.put("recipientType",     s.getRecipientType());
            dto.put("installmentNo",     s.getInstallmentNo());
            dto.put("installmentAmount", s.getInstallmentAmount().toPlainString());
            dto.put("firstYearTotal",    s.getFirstYearTotal().toPlainString());
            if (s.getDueDate() != null) {
                dto.put("dueDate", s.getDueDate().toString());
            }
            scheduleDtos.add(dto);
        }
        payload.put("schedules", scheduleDtos);

        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                policyNumber,                       // 파티션 키
                "InsuranceCommissionConfirmed",     // → lemuel.insurance.commission_confirmed
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("insurance 이벤트 직렬화 실패", e);
        }
    }
}
