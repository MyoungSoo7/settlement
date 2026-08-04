package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 카드 승인 홀드 애그리거트(순수 POJO — 프레임워크 의존 0).
 *
 * <p>{@code authorizationId}(카드사 승인번호 또는 VAN 네트워크 요청 ID)가 자연키이자 멱등 키다.
 * 같은 {@code authorizationId}로 승인이 재요청되면 기존 홀드를 그대로 반환해야 하고,
 * 이 레코드의 UNIQUE 제약이 중복 저장의 최후 차단선이 된다.
 *
 * <p>가용한도 계산(AuthorizeCardService):
 * <pre>
 *   availableMaster = masterLimit − Σ(ACTIVE 홀드, account 단위)
 *   availableSub    = subLimit    − Σ(ACTIVE 홀드, card 단위)
 *   available       = min(availableMaster, availableSub)
 * </pre>
 * 2단계 AC1에서는 매입(CAPTURED)이 별도로 없으므로 ACTIVE 홀드 합계만 차감한다.
 * CAPTURED·PARTIALLY_CAPTURED 가 생기면 위 공식에 Σ매입액도 더한다(AC3).
 */
public class AuthorizationHold {

    private Long id;
    private final String authorizationId;    // 자연키, 멱등 키
    private final Long cardId;
    private final Long cardAccountId;        // 이벤트 파티션 키
    private final Long holderUserId;
    private final BigDecimal amount;
    private HoldStatus status;
    private final String merchantName;
    private final String mcc;               // 4자리 가맹점 업종 코드
    private final Instant authorizedAt;
    private long version;

    private AuthorizationHold(Builder b) {
        this.id = b.id;
        this.authorizationId = Objects.requireNonNull(b.authorizationId, "authorizationId");
        this.cardId = Objects.requireNonNull(b.cardId, "cardId");
        this.cardAccountId = Objects.requireNonNull(b.cardAccountId, "cardAccountId");
        this.holderUserId = Objects.requireNonNull(b.holderUserId, "holderUserId");
        this.amount = requirePositive(b.amount);
        this.status = Objects.requireNonNull(b.status, "status");
        this.merchantName = b.merchantName;
        this.mcc = b.mcc;
        this.authorizedAt = Objects.requireNonNull(b.authorizedAt, "authorizedAt");
        this.version = b.version;
    }

    /**
     * 신규 승인 홀드 생성 — 초기 상태 ACTIVE.
     *
     * <p>이 팩토리는 <b>모든 검증이 끝난 뒤</b>에만 호출해야 한다 — 거절될 요청에
     * 홀드를 만들면 한도가 차감된 채 아무도 사용하지 못한다.
     */
    public static AuthorizationHold create(String authorizationId, Long cardId,
                                            Long cardAccountId, Long holderUserId,
                                            BigDecimal amount, String merchantName,
                                            String mcc, Instant authorizedAt) {
        return builder()
                .authorizationId(authorizationId)
                .cardId(cardId)
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .amount(amount)
                .status(HoldStatus.ACTIVE)
                .merchantName(merchantName)
                .mcc(mcc)
                .authorizedAt(authorizedAt)
                .build();
    }

    /**
     * 매입(전액 또는 부분) — capturedAmount == amount 이면 CAPTURED, 미만이면 PARTIALLY_CAPTURED.
     *
     * <p>ACTIVE 또는 PARTIALLY_CAPTURED 상태에서만 가능하다. 호출자는 {@code capturedAmount} 가
     * 양수이고 {@code amount} 를 초과하지 않음을 보장해야 한다.
     *
     * @param capturedAmount 이번 매입 금액(양수, ≤ amount)
     * @return true if full capture (CAPTURED), false if partial (PARTIALLY_CAPTURED)
     */
    public boolean capture(BigDecimal capturedAmount) {
        if (status != HoldStatus.ACTIVE && status != HoldStatus.PARTIALLY_CAPTURED) {
            throw new IllegalStateException(
                    "ACTIVE 또는 PARTIALLY_CAPTURED 홀드만 매입할 수 있습니다. 현재=" + status);
        }
        if (capturedAmount == null || capturedAmount.signum() <= 0) {
            throw new IllegalArgumentException("매입 금액은 양수여야 합니다: " + capturedAmount);
        }
        if (capturedAmount.compareTo(this.amount) > 0) {
            throw new IllegalArgumentException(
                    "매입 금액이 승인 금액을 초과합니다: " + capturedAmount + " > " + this.amount);
        }
        boolean full = capturedAmount.compareTo(this.amount) >= 0;
        this.status = full ? HoldStatus.CAPTURED : HoldStatus.PARTIALLY_CAPTURED;
        return full;
    }

    /**
     * 환불 — 매입 후 한도 원복.
     * CAPTURED 또는 PARTIALLY_CAPTURED 상태에서만 가능하다.
     */
    public void refund() {
        if (status != HoldStatus.CAPTURED && status != HoldStatus.PARTIALLY_CAPTURED) {
            throw new IllegalStateException(
                    "CAPTURED 또는 PARTIALLY_CAPTURED 홀드만 환불할 수 있습니다. 현재=" + status);
        }
        this.status = HoldStatus.REFUNDED;
    }

    /**
     * 홀드 전액 취소 — 가용한도 복구.
     * ACTIVE 또는 PARTIALLY_CAPTURED 상태에서만 가능하다.
     */
    public void voidHold() {
        if (status != HoldStatus.ACTIVE && status != HoldStatus.PARTIALLY_CAPTURED) {
            throw new IllegalStateException(
                    "ACTIVE 또는 PARTIALLY_CAPTURED 홀드만 취소할 수 있습니다. 현재=" + status);
        }
        this.status = HoldStatus.VOIDED;
    }

    /**
     * 미매입 만료 — 배치가 호출한다. ACTIVE 상태에서만 가능하다.
     */
    public void expire() {
        if (status != HoldStatus.ACTIVE) {
            throw new IllegalStateException(
                    "ACTIVE 홀드만 만료 처리할 수 있습니다. 현재=" + status);
        }
        this.status = HoldStatus.EXPIRED;
    }

    private static BigDecimal requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException("승인 금액은 양수여야 합니다: " + value);
        }
        return value;
    }

    // ── getter ──

    public Long getId() { return id; }
    public String getAuthorizationId() { return authorizationId; }
    public Long getCardId() { return cardId; }
    public Long getCardAccountId() { return cardAccountId; }
    public Long getHolderUserId() { return holderUserId; }
    public BigDecimal getAmount() { return amount; }
    public HoldStatus getStatus() { return status; }
    public String getMerchantName() { return merchantName; }
    public String getMcc() { return mcc; }
    public Instant getAuthorizedAt() { return authorizedAt; }
    public long getVersion() { return version; }

    public static Builder builder() { return new Builder(); }

    /** 영속 계층 재구성 전용 빌더 — 팩토리(create)를 우회하지 않기 위해 패키지 내에서만 노출한다. */
    public static class Builder {
        private Long id;
        private String authorizationId;
        private Long cardId;
        private Long cardAccountId;
        private Long holderUserId;
        private BigDecimal amount;
        private HoldStatus status;
        private String merchantName;
        private String mcc;
        private Instant authorizedAt;
        private long version;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder authorizationId(String v) { this.authorizationId = v; return this; }
        public Builder cardId(Long v) { this.cardId = v; return this; }
        public Builder cardAccountId(Long v) { this.cardAccountId = v; return this; }
        public Builder holderUserId(Long v) { this.holderUserId = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder status(HoldStatus v) { this.status = v; return this; }
        public Builder merchantName(String v) { this.merchantName = v; return this; }
        public Builder mcc(String v) { this.mcc = v; return this; }
        public Builder authorizedAt(Instant v) { this.authorizedAt = v; return this; }
        public Builder version(long v) { this.version = v; return this; }
        public AuthorizationHold build() { return new AuthorizationHold(this); }
    }
}
