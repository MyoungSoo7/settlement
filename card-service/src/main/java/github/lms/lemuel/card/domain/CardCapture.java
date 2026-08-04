package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 카드 매입 도메인 모델(순수 POJO — 프레임워크 의존 0).
 *
 * <p>{@code captureId}(VAN 매입번호)가 자연키이자 멱등 키다. 같은 {@code captureId} 로 매입이
 * 재요청되면 기존 레코드를 그대로 반환해야 한다.
 *
 * <p>하나의 승인({@code authorizationId})에 여러 부분매입이 올 수 있다. 전체 매입 여부는
 * {@code capturedAmount} 합계 vs 승인 금액으로 판단한다.
 *
 * <p>{@code lemuel.card.captured} 이벤트의 정본 계약({@code lemuel.card.captured.schema.json}) 에서
 * required: captureId · authorizationId · cardId · cardAccountId · amount · capturedAt.
 */
public class CardCapture {

    private Long id;
    private final String captureId;          // 자연키, 멱등 키 (VAN 매입번호)
    private final String authorizationId;    // 승인 홀드 참조
    private final Long cardId;
    private final Long cardAccountId;        // 이벤트 파티션 키
    private final Long holderUserId;
    private final BigDecimal capturedAmount;
    private final String merchantName;
    private final Instant capturedAt;

    private CardCapture(Builder b) {
        this.id = b.id;
        this.captureId = Objects.requireNonNull(b.captureId, "captureId");
        this.authorizationId = Objects.requireNonNull(b.authorizationId, "authorizationId");
        this.cardId = Objects.requireNonNull(b.cardId, "cardId");
        this.cardAccountId = Objects.requireNonNull(b.cardAccountId, "cardAccountId");
        this.holderUserId = Objects.requireNonNull(b.holderUserId, "holderUserId");
        this.capturedAmount = requirePositive(b.capturedAmount);
        this.merchantName = b.merchantName;
        this.capturedAt = Objects.requireNonNull(b.capturedAt, "capturedAt");
    }

    /**
     * 신규 매입 생성 팩토리.
     */
    public static CardCapture create(String captureId, String authorizationId,
                                     Long cardId, Long cardAccountId, Long holderUserId,
                                     BigDecimal capturedAmount, String merchantName,
                                     Instant capturedAt) {
        return builder()
                .captureId(captureId)
                .authorizationId(authorizationId)
                .cardId(cardId)
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .capturedAmount(capturedAmount)
                .merchantName(merchantName)
                .capturedAt(capturedAt)
                .build();
    }

    private static BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("매입 금액은 양수여야 합니다: " + value);
        }
        return value;
    }

    // ── getter ──

    public Long getId() { return id; }
    public String getCaptureId() { return captureId; }
    public String getAuthorizationId() { return authorizationId; }
    public Long getCardId() { return cardId; }
    public Long getCardAccountId() { return cardAccountId; }
    public Long getHolderUserId() { return holderUserId; }
    public BigDecimal getCapturedAmount() { return capturedAmount; }
    public String getMerchantName() { return merchantName; }
    public Instant getCapturedAt() { return capturedAt; }

    public static Builder builder() { return new Builder(); }

    /** 영속 계층 재구성 전용 빌더 */
    public static class Builder {
        private Long id;
        private String captureId;
        private String authorizationId;
        private Long cardId;
        private Long cardAccountId;
        private Long holderUserId;
        private BigDecimal capturedAmount;
        private String merchantName;
        private Instant capturedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder captureId(String v) { this.captureId = v; return this; }
        public Builder authorizationId(String v) { this.authorizationId = v; return this; }
        public Builder cardId(Long v) { this.cardId = v; return this; }
        public Builder cardAccountId(Long v) { this.cardAccountId = v; return this; }
        public Builder holderUserId(Long v) { this.holderUserId = v; return this; }
        public Builder capturedAmount(BigDecimal v) { this.capturedAmount = v; return this; }
        public Builder merchantName(String v) { this.merchantName = v; return this; }
        public Builder capturedAt(Instant v) { this.capturedAt = v; return this; }
        public CardCapture build() { return new CardCapture(this); }
    }
}
