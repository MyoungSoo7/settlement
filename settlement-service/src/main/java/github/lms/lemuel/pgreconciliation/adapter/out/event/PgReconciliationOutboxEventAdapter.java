package github.lms.lemuel.pgreconciliation.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import github.lms.lemuel.pgreconciliation.application.port.out.PublishDiscrepancyResolvedEventPort;
import github.lms.lemuel.pgreconciliation.domain.ReconciliationDiscrepancy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PG 대사 차이 해소 이벤트를 Transactional Outbox 에 기록한다.
 *
 * <p>aggregateType="PgReconciliation" → 토픽 lemuel.pgreconciliation.discrepancy_approved.
 * 페이로드는 후속 보정 핸들러가 타입별(과/소 정산) 부호 규칙으로 정산을 조정하는 데 필요한 컨텍스트를 담는다.
 */
@Component
public class PgReconciliationOutboxEventAdapter implements PublishDiscrepancyResolvedEventPort {

    private static final String AGGREGATE_TYPE = "PgReconciliation";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public PgReconciliationOutboxEventAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                              ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishDiscrepancyApproved(ReconciliationDiscrepancy d) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("discrepancyId", d.getId());
        payload.put("runId", d.getRunId());
        payload.put("type", d.getType() != null ? d.getType().name() : null);
        payload.put("paymentId", d.getPaymentId());
        payload.put("pgTransactionId", d.getPgTransactionId());
        payload.put("internalAmount", d.getInternalAmount());
        payload.put("pgAmount", d.getPgAmount());
        payload.put("difference", d.getDifference()); // pgAmount - internalAmount (signed)
        payload.put("resolvedBy", d.getResolvedBy());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE, String.valueOf(d.getId()),
                "PgReconciliationDiscrepancyApproved", toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("PG 대사 이벤트 직렬화 실패", e);
        }
    }
}
