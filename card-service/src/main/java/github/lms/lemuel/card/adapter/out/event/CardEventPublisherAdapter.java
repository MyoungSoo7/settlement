package github.lms.lemuel.card.adapter.out.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.lms.lemuel.card.application.port.out.PublishCardEventPort;
import github.lms.lemuel.card.domain.AuthorizationHold;
import github.lms.lemuel.card.domain.Card;
import github.lms.lemuel.card.domain.CardAccount;
import github.lms.lemuel.card.domain.CardAccountStatus;
import github.lms.lemuel.card.domain.CardCapture;
import github.lms.lemuel.card.domain.CardStatement;
import github.lms.lemuel.card.domain.CardStatus;
import github.lms.lemuel.card.domain.LimitChangeResult;
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

    /**
     * 마스터 한도 변경 — 토픽 {@code lemuel.card.limit_changed}, {@code scope=MASTER}.
     *
     * <p>{@code cardId} 를 <b>명시적으로 null 로</b> 싣는다. 필드를 아예 빼면 소비자가 "키가 없다"와
     * "값이 없다"를 구분해야 하고, 한 토픽이 scope 별로 다른 모양을 갖게 된다 — 그러면 스키마가
     * 두 개인 것과 다름없어진다.
     */
    @Override
    public void publishMasterLimitChanged(CardAccount account, BigDecimal previousLimit,
                                          LimitChangeResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardAccountId", account.getId());
        payload.put("cardId", null);
        payload.put("organizationId", account.getOrganizationId());
        payload.put("previousLimit", previousLimit.toPlainString());
        payload.put("newLimit", result.appliedLimit().toPlainString());
        payload.put("clamped", result.clamped());
        payload.put("scope", "MASTER");
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(account.getId()),
                "CardLimitChanged",
                toJson(payload)));
    }

    /**
     * 카드계정 상태 변경 — 토픽 {@code lemuel.card.account_status_changed}.
     *
     * <p>{@code masterLimit} 을 함께 싣는 이유: 재산정이 계정을 정지시킬 때 한도는 Σ서브한도로
     * 클램프된 채 남는다. 상태만 보낸 소비자는 "정지인데 한도가 왜 살아 있나"를 우리에게 물어야
     * 하지만, 값이 실려 있으면 그 자리에서 답이 된다.
     */
    @Override
    public void publishAccountStatusChanged(CardAccount account, CardAccountStatus previousStatus,
                                            String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardAccountId", account.getId());
        payload.put("organizationId", account.getOrganizationId());
        payload.put("sellerId", account.getSellerId());
        payload.put("previousStatus", previousStatus.name());
        payload.put("newStatus", account.getStatus().name());
        payload.put("masterLimit", account.getMasterLimit().toPlainString());
        payload.put("reason", reason);
        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(account.getId()),
                "CardAccountStatusChanged",
                toJson(payload)));
    }

    /**
     * 카드 승인 — 토픽 {@code lemuel.card.authorized}.
     *
     * <p>파티션 키: {@code cardAccountId}(1단계 카드 이벤트와 동일) — 같은 계정의
     * 발급·한도변경·승인이 같은 파티션에 순서대로 떨어져야 소비자가 상태를 일관되게 본다.
     *
     * <p>금액은 반드시 {@link java.math.BigDecimal#toPlainString()} 으로 직렬화한다
     * (DATA-STANDARD N5, 계약 {@code lemuel.card.authorized.schema.json} 강제).
     * 숫자로 실으면 소비자 언어에 따라 double 로 파싱돼 원 단위가 소리 없이 어긋난다 —
     * 이 이벤트는 GL 분개까지 흘러가므로 그 오차가 곧 장부 오차다.
     *
     * <p>{@code remainingSubLimit} 은 optional 이지만 승인 경로에서 계산 가능하므로 실는다 —
     * 소비자가 자체 재계산 없이 한도 현황을 즉시 알 수 있다.
     */
    @Override
    public void publishAuthorized(AuthorizationHold hold, Card card, CardAccount account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authorizationId", hold.getAuthorizationId());
        payload.put("cardId", hold.getCardId());
        payload.put("cardAccountId", hold.getCardAccountId());
        payload.put("holderUserId", hold.getHolderUserId());
        payload.put("amount", hold.getAmount().toPlainString());
        // 잔여 서브한도 = 서브한도 - 이 승인 금액(이미 저장된 홀드가 가용한도에서 차감됨)
        // 정확한 잔여 서브한도는 다른 ACTIVE 홀드를 모두 합산해야 하지만, 승인 직후라
        // card.getSubLimit() - hold.getAmount() 가 "이 승인만 반영된" 근사값이다.
        // 소비자가 검증용으로 쓸 수 있도록 optional 필드로 싣는다(계약상 optional 임).
        BigDecimal remaining = card.getSubLimit().subtract(hold.getAmount());
        if (remaining.signum() >= 0) {
            payload.put("remainingSubLimit", remaining.toPlainString());
        }
        if (hold.getMerchantName() != null) {
            payload.put("merchantName", hold.getMerchantName());
        }
        payload.put("authorizedAt", hold.getAuthorizedAt().toString());

        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(hold.getCardAccountId()),   // 파티션 키 = cardAccountId
                "CardAuthorized",                          // → 토픽 lemuel.card.authorized
                toJson(payload)));
    }

    /**
     * 카드 매입(capture) — 토픽 {@code lemuel.card.captured}.
     *
     * <p>계약 스키마({@code lemuel.card.captured.schema.json}) required 필드:
     * captureId · authorizationId · cardId · cardAccountId · amount · capturedAt.
     *
     * <p>파티션 키: {@code cardAccountId} — 1단계 카드 이벤트·2단계 승인 이벤트와 동일.
     * 같은 계정의 발급·승인·매입이 같은 파티션에 순서대로 떨어져야 소비자가 일관된 순서를 본다.
     *
     * <p>금액({@code amount})은 반드시 {@link java.math.BigDecimal#toPlainString()} 으로
     * 직렬화한다(DATA-STANDARD N5, 계약 강제) — 부동소수 파싱 오차가 GL 분개 오차가 된다.
     */
    @Override
    public void publishCaptured(CardCapture capture, AuthorizationHold hold) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("captureId", capture.getCaptureId());
        payload.put("authorizationId", capture.getAuthorizationId());
        payload.put("cardId", capture.getCardId());
        payload.put("cardAccountId", capture.getCardAccountId());
        payload.put("holderUserId", capture.getHolderUserId());
        // amount = 이번 매입 금액(부분 매입에서 승인 금액과 다를 수 있다) — DATA-STANDARD N5
        payload.put("amount", capture.getCapturedAmount().toPlainString());
        if (capture.getMerchantName() != null) {
            payload.put("merchantName", capture.getMerchantName());
        }
        payload.put("capturedAt", capture.getCapturedAt().toString());

        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(capture.getCardAccountId()),   // 파티션 키 = cardAccountId
                "CardCaptured",                               // → 토픽 lemuel.card.captured
                toJson(payload)));
    }

    /**
     * 청구서 전액 납부 — 토픽 {@code lemuel.card.statement.paid}.
     *
     * <p>계약 스키마({@code lemuel.card.statement.paid.schema.json}) required 필드:
     * statementId · cardAccountId · billingYearMonth · paidAmount · paymentId · paidAt.
     *
     * <p>파티션 키: {@code cardAccountId} — 1단계 카드 이벤트·2단계 승인·매입과 동일.
     * 같은 계정의 발급·승인·매입·청구 이벤트가 같은 파티션에 순서대로 떨어져야 소비자(GL 분개)가
     * 일관된 순서를 본다.
     *
     * <p>금액({@code paidAmount})은 반드시 {@code toPlainString()} 으로 직렬화한다(DATA-STANDARD N5).
     */
    @Override
    public void publishStatementPaid(CardStatement statement, String paymentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statementId", statement.getId());
        payload.put("cardAccountId", statement.getCardAccountId());
        payload.put("billingYearMonth", statement.getBillingYearMonth().toString());
        // paidAmount = 전체 납부 금액(누적) — DATA-STANDARD N5
        payload.put("paidAmount", statement.getPaidAmount().toPlainString());
        payload.put("totalAmount", statement.getTotalAmount().toPlainString());
        payload.put("paymentId", paymentId);
        payload.put("paidAt", java.time.Instant.now().toString());

        saveOutboxEventPort.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                String.valueOf(statement.getCardAccountId()),  // 파티션 키 = cardAccountId
                "CardStatementPaid",                           // → 토픽 lemuel.card.statement.paid
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
