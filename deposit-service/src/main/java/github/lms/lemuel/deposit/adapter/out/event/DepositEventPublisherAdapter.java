package github.lms.lemuel.deposit.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.deposit.application.port.out.PublishDepositEventPort;
import github.lms.lemuel.deposit.domain.DepositEntry;
import github.lms.lemuel.deposit.domain.DepositHold;
import github.lms.lemuel.deposit.domain.DepositOffsetShortfall;
import github.lms.lemuel.deposit.domain.SellerDepositAccount;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 예치금 도메인 이벤트를 Transactional Outbox 에 기록한다.
 *
 * <p>aggregateType="Deposit", eventType 으로 라우팅 → 토픽 lemuel.deposit.*
 * 금액은 DATA-STANDARD N5(BigDecimal.toPlainString) 으로 직렬화한다.
 */
@Component
public class DepositEventPublisherAdapter implements PublishDepositEventPort {

    private static final String AGGREGATE_TYPE = "Deposit";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public DepositEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                        @Qualifier("outboxObjectMapper") ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishBalanceChanged(SellerDepositAccount account, String triggerEventType) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sellerId", account.getSellerId());
        payload.put("available", account.getAvailable().toPlainString());
        payload.put("locked", account.getLocked().toPlainString());
        payload.put("total", account.getTotal().toPlainString());
        payload.put("triggerEventType", triggerEventType);
        save(String.valueOf(account.getSellerId()), "DepositBalanceChanged", payload);
    }

    @Override
    public void publishHoldPlaced(DepositHold hold, SellerDepositAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdId", hold.getId());
        payload.put("sellerId", account.getSellerId());
        payload.put("holderType", hold.getHolderType().name());
        payload.put("holderReference", hold.getHolderReference());
        payload.put("amount", hold.getOriginalAmount().toPlainString());
        payload.put("available", account.getAvailable().toPlainString());
        payload.put("locked", account.getLocked().toPlainString());
        save(String.valueOf(account.getSellerId()), "DepositHoldPlaced", payload);
    }

    @Override
    public void publishHoldReleased(DepositHold hold, SellerDepositAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holdId", hold.getId());
        payload.put("sellerId", account.getSellerId());
        payload.put("holderType", hold.getHolderType().name());
        payload.put("holderReference", hold.getHolderReference());
        payload.put("releasedAmount", hold.getRemainingAmount().toPlainString());
        payload.put("available", account.getAvailable().toPlainString());
        save(String.valueOf(account.getSellerId()), "DepositHoldReleased", payload);
    }

    @Override
    public void publishOffsetApplied(DepositEntry entry, SellerDepositAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entryId", entry.getId());
        payload.put("sellerId", account.getSellerId());
        payload.put("amount", entry.getAmount().toPlainString());
        payload.put("referenceId", entry.getReferenceId());
        payload.put("referenceType", entry.getReferenceType());
        payload.put("offsetSequence", entry.getOffsetSequence());
        payload.put("sourceHoldId", entry.getSourceHoldId());
        payload.put("available", account.getAvailable().toPlainString());
        payload.put("total", account.getTotal().toPlainString());
        save(String.valueOf(account.getSellerId()), "DepositOffsetApplied", payload);
    }

    @Override
    public void publishOffsetShortfall(DepositOffsetShortfall shortfall) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shortfallId", shortfall.getId());
        payload.put("sellerId", shortfall.getSellerId());
        payload.put("holderType", shortfall.getHolderType().name());
        payload.put("holderReference", shortfall.getHolderReference());
        payload.put("requestedAmount", shortfall.getRequestedAmount().toPlainString());
        payload.put("appliedAmount", shortfall.getAppliedAmount().toPlainString());
        payload.put("shortfallAmount", shortfall.getShortfallAmount().toPlainString());
        payload.put("sourceHoldId", shortfall.getSourceHoldId());
        payload.put("occurredAt", shortfall.getOccurredAt().toString());
        save(String.valueOf(shortfall.getSellerId()), "DepositOffsetShortfall", payload);
    }

    private void save(String aggregateId, String eventType, Map<String, Object> payload) {
        saveOutboxEventPort.save(OutboxEvent.pending(AGGREGATE_TYPE, aggregateId, eventType, toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("deposit 이벤트 직렬화 실패", e);
        }
    }
}
