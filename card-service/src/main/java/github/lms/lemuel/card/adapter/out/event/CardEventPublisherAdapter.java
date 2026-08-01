package github.lms.lemuel.card.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.common.outbox.application.port.out.SaveOutboxEventPort;
import github.lms.lemuel.common.outbox.domain.OutboxEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 카드 도메인 이벤트를 Transactional Outbox 에 기록한다 — 도메인 트랜잭션과 같은 트랜잭션에서
 * 저장되어 원자성이 보장되고, shared-common OutboxPublisherScheduler 가
 * aggregateType="Card" + eventType 으로 라우팅해 토픽 {@code lemuel.card.account_opened} ·
 * {@code lemuel.card.issued} 로 발행한다.
 *
 * <p>금액은 <b>문자열</b>로 싣는다(DATA-STANDARD N5) — 여신 금액이 부동소수 왕복으로 어긋나면
 * 소비자마다 다른 한도를 보게 된다.
 */
@Component
public class CardEventPublisherAdapter implements PublishCardEventPort {

    private static final String AGGREGATE_TYPE = "Card";

    private final SaveOutboxEventPort saveOutboxEventPort;
    private final ObjectMapper objectMapper;

    public CardEventPublisherAdapter(SaveOutboxEventPort saveOutboxEventPort,
                                     @Qualifier("outboxObjectMapper") ObjectMapper objectMapper) {
        this.saveOutboxEventPort = saveOutboxEventPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishAccountOpened(CardAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardAccountId", account.getId());
        payload.put("organizationId", account.getOrganizationId());
        payload.put("sellerId", account.getSellerId());
        payload.put("masterLimit", account.getMasterLimit().toPlainString());
        // ACTIVE 전이는 근거(LimitSnapshot) 없이는 불가능하므로 여기서 null 이 될 수 없다.
        payload.put("reputationGrade", account.getLimitSnapshot().reputationGrade().name());
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(account.getId()),
                "CardAccountOpened",
                toJson(payload)));
    }

    @Override
    public void publishIssued(Card card, CardAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", card.getId());
        payload.put("cardAccountId", account.getId());
        payload.put("organizationId", account.getOrganizationId());
        payload.put("holderUserId", card.getHolderUserId());
        // 마스킹된 번호만 싣는다 — 원본 PAN 은 CardIssuerPort 를 넘어오지 않으므로 여기에도 없다.
        payload.put("maskedCardNo", card.getMaskedCardNo());
        payload.put("subLimit", card.getSubLimit().toPlainString());
        // aggregateId 는 카드가 아니라 <b>카드계정</b>이다 — 같은 계정의 발급·한도변경 이벤트가
        // 같은 파티션에 떨어져야 소비자가 "한도 배분 순서"를 뒤집힌 채로 보지 않는다.
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(account.getId()),
                "CardIssued",
                toJson(payload)));
    }

    /**
     * 서브한도 변경 — 토픽 {@code lemuel.card.limit_changed}.
     *
     * <p>{@code scope} 를 명시하는 이유는 마스터 한도 변경(Task 13)이 <b>같은 토픽</b>으로 나가기
     * 때문이다. 구분자가 없으면 소비자가 "cardId 가 있으면 서브겠지" 같은 모양 추측으로 분기하게
     * 되고, 그 추측은 페이로드에 필드 하나가 추가되는 순간 조용히 틀린다.
     *
     * <p>{@code clamped} 는 서브한도에선 항상 false 다 — 클램프는 마스터 하향이 Σ서브한도 하한에
     * 걸릴 때만 일어난다. 그래도 필드를 빼지 않는 것은, 소비자가 scope 별로 다른 스키마를 다루지
     * 않게 하기 위해서다(한 토픽 = 한 모양).
     */
    @Override
    public void publishSubLimitChanged(Card card, CardAccount account, BigDecimal previousSubLimit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardAccountId", account.getId());
        payload.put("cardId", card.getId());
        payload.put("organizationId", account.getOrganizationId());
        payload.put("previousLimit", previousSubLimit.toPlainString());
        payload.put("newLimit", card.getSubLimit().toPlainString());
        payload.put("clamped", false);
        payload.put("scope", "SUB");
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(account.getId()),
                "CardLimitChanged",
                toJson(payload)));
    }

    /**
     * 카드 상태 변경 — 토픽 {@code lemuel.card.status_changed}.
     *
     * <p>파티션 키는 여기서도 카드계정이다 — 같은 계정의 발급·한도변경·상태변경이 한 파티션에
     * 순서대로 떨어져야 소비자가 "정지된 카드에 한도를 올렸다" 같은 뒤집힌 이력을 보지 않는다.
     */
    @Override
    public void publishStatusChanged(Card card, CardAccount account,
                                     CardStatus previousStatus, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", card.getId());
        payload.put("cardAccountId", account.getId());
        payload.put("organizationId", account.getOrganizationId());
        payload.put("holderUserId", card.getHolderUserId());
        payload.put("previousStatus", previousStatus.name());
        payload.put("newStatus", card.getStatus().name());
        payload.put("reason", reason);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(account.getId()),
                "CardStatusChanged",
                toJson(payload)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("card 이벤트 직렬화 실패", e);
        }
    }
}
