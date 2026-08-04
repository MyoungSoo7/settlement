package github.lms.lemuel.card.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

/**
 * 가맹점/MCC 지출정책 도메인 엔티티(순수 POJO — 프레임워크 의존 0).
 *
 * <p>카드계정 단위({@code cardId == null}) 또는 카드 단위({@code cardId != null})로 설정된다.
 * 카드 단위 정책이 있으면 계정 단위 정책보다 우선 적용된다.
 *
 * <p>정책 평가 결과는 항상 {@link DeclineReason#MERCHANT_POLICY_VIOLATION} 이고
 * 세부 사유(MCC 차단인지 한도 초과인지)는 로그에만 남긴다 — 거절 메시지를 세분화하면
 * 가맹점 측이 우회 경로를 쉽게 찾는다.
 */
public class MerchantPolicy {

    private Long id;
    private final Long cardAccountId;
    private final Long cardId;              // null = 계정 단위 정책
    private final Set<String> blockedMccs;  // 차단 MCC 목록
    private final Set<String> allowedMccs;  // 허용 MCC 목록(비어 있으면 전체 허용)
    private final BigDecimal maxPerTransactionAmount;  // 1회 최대 승인 금액(null=무제한)
    private final BigDecimal dailySpendLimit;           // 일 한도(null=무제한)
    private final BigDecimal monthlySpendLimit;         // 월 한도(null=무제한)
    private final boolean overseasEnabled;   // 해외 거래 허용 여부
    private final boolean onlineEnabled;     // 온라인 거래 허용 여부

    private MerchantPolicy(Builder b) {
        this.id = b.id;
        this.cardAccountId = b.cardAccountId;
        this.cardId = b.cardId;
        this.blockedMccs = b.blockedMccs != null ? Set.copyOf(b.blockedMccs) : Set.of();
        this.allowedMccs = b.allowedMccs != null ? Set.copyOf(b.allowedMccs) : Set.of();
        this.maxPerTransactionAmount = b.maxPerTransactionAmount;
        this.dailySpendLimit = b.dailySpendLimit;
        this.monthlySpendLimit = b.monthlySpendLimit;
        this.overseasEnabled = b.overseasEnabled;
        this.onlineEnabled = b.onlineEnabled;
    }

    /**
     * 가맹점/MCC 정책 위반 여부를 평가한다.
     *
     * @param mcc          4자리 가맹점 업종코드(null 허용 — 코드 없는 가맹점도 존재)
     * @param amount       승인 요청 금액
     * @param overseas     해외 거래 여부
     * @param online       온라인 거래 여부
     * @param todaySpend   오늘 이미 사용한 금액(ACTIVE + CAPTURED 홀드 합계)
     * @param monthlySpend 이번 달 이미 사용한 금액
     * @return 정책 위반 시 {@code Optional.of(MERCHANT_POLICY_VIOLATION)}, 통과 시 empty
     */
    public Optional<DeclineReason> evaluate(String mcc, BigDecimal amount,
                                             boolean overseas, boolean online,
                                             BigDecimal todaySpend, BigDecimal monthlySpend) {
        // 차단 MCC 확인
        if (mcc != null && blockedMccs.contains(mcc)) {
            return Optional.of(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
        // 허용 MCC 목록이 있으면 목록 외 MCC는 거절
        if (!allowedMccs.isEmpty() && (mcc == null || !allowedMccs.contains(mcc))) {
            return Optional.of(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
        // 해외 거래 불허
        if (overseas && !overseasEnabled) {
            return Optional.of(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
        // 온라인 거래 불허
        if (online && !onlineEnabled) {
            return Optional.of(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
        // 1회 한도 초과
        if (maxPerTransactionAmount != null && amount.compareTo(maxPerTransactionAmount) > 0) {
            return Optional.of(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
        // 일 한도 초과
        BigDecimal safeToday = todaySpend != null ? todaySpend : BigDecimal.ZERO;
        if (dailySpendLimit != null && safeToday.add(amount).compareTo(dailySpendLimit) > 0) {
            return Optional.of(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
        // 월 한도 초과
        BigDecimal safeMonthly = monthlySpend != null ? monthlySpend : BigDecimal.ZERO;
        if (monthlySpendLimit != null && safeMonthly.add(amount).compareTo(monthlySpendLimit) > 0) {
            return Optional.of(DeclineReason.MERCHANT_POLICY_VIOLATION);
        }
        return Optional.empty();
    }

    // ── getter ──

    public Long getId() { return id; }
    public Long getCardAccountId() { return cardAccountId; }
    public Long getCardId() { return cardId; }
    public Set<String> getBlockedMccs() { return blockedMccs; }
    public Set<String> getAllowedMccs() { return allowedMccs; }
    public BigDecimal getMaxPerTransactionAmount() { return maxPerTransactionAmount; }
    public BigDecimal getDailySpendLimit() { return dailySpendLimit; }
    public BigDecimal getMonthlySpendLimit() { return monthlySpendLimit; }
    public boolean isOverseasEnabled() { return overseasEnabled; }
    public boolean isOnlineEnabled() { return onlineEnabled; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Long cardAccountId;
        private Long cardId;
        private Set<String> blockedMccs;
        private Set<String> allowedMccs;
        private BigDecimal maxPerTransactionAmount;
        private BigDecimal dailySpendLimit;
        private BigDecimal monthlySpendLimit;
        private boolean overseasEnabled = true;
        private boolean onlineEnabled = true;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder cardAccountId(Long v) { this.cardAccountId = v; return this; }
        public Builder cardId(Long v) { this.cardId = v; return this; }
        public Builder blockedMccs(Set<String> v) { this.blockedMccs = v; return this; }
        public Builder allowedMccs(Set<String> v) { this.allowedMccs = v; return this; }
        public Builder maxPerTransactionAmount(BigDecimal v) { this.maxPerTransactionAmount = v; return this; }
        public Builder dailySpendLimit(BigDecimal v) { this.dailySpendLimit = v; return this; }
        public Builder monthlySpendLimit(BigDecimal v) { this.monthlySpendLimit = v; return this; }
        public Builder overseasEnabled(boolean v) { this.overseasEnabled = v; return this; }
        public Builder onlineEnabled(boolean v) { this.onlineEnabled = v; return this; }
        public MerchantPolicy build() { return new MerchantPolicy(this); }
    }
}
