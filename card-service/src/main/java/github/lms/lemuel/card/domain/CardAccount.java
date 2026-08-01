package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;
import github.lms.lemuel.card.domain.exception.SubLimitExceededException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 법인 카드계정 애그리거트 루트(순수 POJO — 프레임워크 의존 0).
 *
 * <p>셀러 법인 단위로 하나 존재하며, 재원(정산 확정·미지급금 + 홀드백)과 평판등급으로 산정된
 * 마스터 한도를 갖는다. 임직원 카드(Card)는 이 마스터 한도 안에서 서브한도를 배분받는다 —
 * 그 불변식({@code masterLimit >= Σ subLimit})을 지키는 것이 이 애그리거트의 핵심 책임이다.
 *
 * <pre>
 * SCREENING → ACTIVE ⇄ SUSPENDED → CLOSED
 *           ↘ REJECTED
 * </pre>
 */
public class CardAccount {

    private Long id;
    private final Long organizationId;
    private final String sellerId;
    private CardAccountStatus status;
    private BigDecimal masterLimit;
    private LimitSnapshot limitSnapshot;
    private String rejectReason;
    private long version;

    private CardAccount(Builder b) {
        this.id = b.id;
        this.organizationId = Objects.requireNonNull(b.organizationId, "organizationId");
        this.sellerId = Objects.requireNonNull(b.sellerId, "sellerId");
        this.status = Objects.requireNonNull(b.status, "status");
        this.masterLimit = Objects.requireNonNull(b.masterLimit, "masterLimit");
        this.limitSnapshot = b.limitSnapshot;
        this.rejectReason = b.rejectReason;
        this.version = b.version;
    }

    /** 신규 카드계정 개설 — 심사가 끝나기 전이라 마스터 한도는 0, 상태는 SCREENING. */
    public static CardAccount open(Long organizationId, String sellerId) {
        return builder()
                .organizationId(organizationId)
                .sellerId(sellerId)
                .status(CardAccountStatus.SCREENING)
                .masterLimit(BigDecimal.ZERO)
                .build();
    }

    /**
     * 심사 통과 → ACTIVE. 산정된 마스터 한도와 그 근거({@link LimitSnapshot})를 함께 남긴다 —
     * 근거 없는 한도를 남기지 않는다는 원칙(LimitSnapshot 자체의 compact 생성자가 강제)을
     * 애그리거트 차원에서도 지킨다.
     */
    public void activate(BigDecimal masterLimit, LimitSnapshot snapshot) {
        transitionTo(CardAccountStatus.ACTIVE);
        requireNonNegative(masterLimit);
        if (snapshot == null) {
            throw new IllegalArgumentException("ACTIVE 전이는 한도 산정 근거(LimitSnapshot)가 필수입니다");
        }
        this.masterLimit = masterLimit;
        this.limitSnapshot = snapshot;
    }

    /** 심사 탈락(사유·근거 없음) — 운영 편의를 위한 최소 오버로드. */
    public void reject() {
        reject(null, null);
    }

    /** 심사 탈락 + 사유. */
    public void reject(String reason) {
        reject(reason, null);
    }

    /**
     * 심사 탈락 + 사유 + 산정 근거. 탈락도 {@link LimitSnapshot} 을 남길 수 있다 —
     * "재원·평판을 계산은 했지만 기준 미달로 떨어졌다"는 근거를 사후에 재현하기 위해서다
     * (근거 없는 거절을 남기지 않는다).
     */
    public void reject(String reason, LimitSnapshot snapshot) {
        transitionTo(CardAccountStatus.REJECTED);
        this.rejectReason = reason;
        this.limitSnapshot = snapshot;
    }

    public void suspend() {
        transitionTo(CardAccountStatus.SUSPENDED);
    }

    public void resume() {
        transitionTo(CardAccountStatus.ACTIVE);
    }

    public void close() {
        transitionTo(CardAccountStatus.CLOSED);
    }

    /** 발급 가능 여부 검증 — 불변식 masterLimit >= Σ subLimit 의 도메인 표현. */
    public void assertCanIssue(BigDecimal currentSubLimitSum, BigDecimal newSubLimit) {
        if (status != CardAccountStatus.ACTIVE) {
            throw new InvalidCardTransitionException(
                    "ACTIVE 카드계정만 카드를 발급할 수 있습니다. 현재=" + status);
        }
        requireNonNegative(newSubLimit);
        BigDecimal after = currentSubLimitSum.add(newSubLimit);
        if (after.compareTo(masterLimit) > 0) {
            throw new SubLimitExceededException(masterLimit, currentSubLimitSum, newSubLimit);
        }
    }

    /**
     * 마스터 한도 변경. 상향은 그대로, 하향은 Σ서브한도를 하한으로 클램프한다.
     *
     * <p>이미 배분한 임직원 한도 아래로 마스터를 내리면 카드가 사전 통지 없이 무력화된다.
     * 재산정이 자동으로 도는 경로라 특히 위험해서, 도메인에서 하한을 강제한다.
     */
    public LimitChangeResult changeMasterLimit(BigDecimal newLimit, BigDecimal currentSubLimitSum) {
        requireMutable();
        requireNonNegative(newLimit);
        boolean clamped = newLimit.compareTo(currentSubLimitSum) < 0;
        BigDecimal applied = clamped ? currentSubLimitSum : newLimit;
        this.masterLimit = applied;
        return new LimitChangeResult(applied, clamped);
    }

    // ── 상태 전이 가드 ──

    private void transitionTo(CardAccountStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidCardTransitionException(status, target);
        }
        this.status = target;
    }

    /** ACTIVE·SUSPENDED 만 한도 변경 가능 — CLOSED/REJECTED/SCREENING 은 불변. */
    private void requireMutable() {
        if (status != CardAccountStatus.ACTIVE && status != CardAccountStatus.SUSPENDED) {
            throw new InvalidCardTransitionException(
                    "ACTIVE 또는 SUSPENDED 카드계정만 한도를 변경할 수 있습니다. 현재=" + status);
        }
    }

    private static void requireNonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("한도는 음수일 수 없습니다: " + value);
        }
    }

    // ── getter ──

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public CardAccountStatus getStatus() {
        return status;
    }

    public BigDecimal getMasterLimit() {
        return masterLimit;
    }

    public LimitSnapshot getLimitSnapshot() {
        return limitSnapshot;
    }

    /** 심사 탈락 사유(reject 시 기록). 탈락하지 않았거나 사유 없이 탈락했으면 null. */
    public String getRejectReason() {
        return rejectReason;
    }

    public long getVersion() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 정적 팩토리(open)와 영속성 어댑터의 재구성이 공용하는 빌더. */
    public static class Builder {
        private Long id;
        private Long organizationId;
        private String sellerId;
        private CardAccountStatus status;
        private BigDecimal masterLimit;
        private LimitSnapshot limitSnapshot;
        private String rejectReason;
        private long version;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder organizationId(Long v) {
            this.organizationId = v;
            return this;
        }

        public Builder sellerId(String v) {
            this.sellerId = v;
            return this;
        }

        public Builder status(CardAccountStatus v) {
            this.status = v;
            return this;
        }

        public Builder masterLimit(BigDecimal v) {
            this.masterLimit = v;
            return this;
        }

        public Builder limitSnapshot(LimitSnapshot v) {
            this.limitSnapshot = v;
            return this;
        }

        public Builder rejectReason(String v) {
            this.rejectReason = v;
            return this;
        }

        public Builder version(long v) {
            this.version = v;
            return this;
        }

        public CardAccount build() {
            return new CardAccount(this);
        }
    }
}
