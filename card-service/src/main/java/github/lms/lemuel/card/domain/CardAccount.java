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
        this.masterLimit = requireNonNegative(b.masterLimit);
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
     *
     * <p><b>검증을 상태 전이보다 먼저 한다</b> — 반대 순서였다면 snapshot 이 null 이라 예외가
     * 던져져도 status 는 이미 ACTIVE 로 바뀐 채 남아, masterLimit=0·limitSnapshot=null 인
     * "ACTIVE 인데 근거 없는" 반쯤 깨진 상태가 되고 재시도(ACTIVE→ACTIVE 는 금지 전이)도 불가능해진다.
     * 검증을 먼저 해서 실패 시 SCREENING 에 그대로 남겨 재시도를 가능하게 한다.
     */
    public void activate(BigDecimal masterLimit, LimitSnapshot snapshot) {
        requireNonNegative(masterLimit);
        if (snapshot == null) {
            throw new IllegalArgumentException("ACTIVE 전이는 한도 산정 근거(LimitSnapshot)가 필수입니다");
        }
        transitionTo(CardAccountStatus.ACTIVE);
        this.masterLimit = masterLimit;
        this.limitSnapshot = snapshot;
    }

    /**
     * 심사 탈락 + 사유 + 산정 근거. 탈락도 {@link LimitSnapshot} 을 항상 남긴다 —
     * "재원·평판을 계산은 했지만 기준 미달로 떨어졌다"는 근거를 사후에 재현하기 위해서다
     * (근거 없는 거절을 남기지 않는다). Task 5 {@code CardLimitPolicy.screen()} 은 승인·탈락
     * 어느 쪽이든 항상 LimitSnapshot 을 반환하므로, 이 시그니처가 유일한 정본이다
     * (reason/snapshot 을 생략하는 오버로드는 실사용처가 없어 제거했다).
     *
     * <p><b>검증을 상태 전이보다 먼저 한다</b> — {@link #activate} 와 동형의 이유다: snapshot·reason
     * 없이 상태만 REJECTED 로 바뀐 채 예외가 던져지면 "근거 없는 거절"이 만들어지고, REJECTED 는
     * 터미널 상태라 재시도도 불가능해진다. 검증을 먼저 해서 실패 시 SCREENING 에 남겨 재시도를 가능하게 한다.
     * ({@code ScreeningResult} 를 거치지 않는 거절 경로 — 예: type≠SELLER, externalRef 해석 실패 —
     * 가 생기더라도 이 게이트가 근거 없는 거절을 막는다.)
     */
    public void reject(String reason, LimitSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("REJECTED 전이는 한도 산정 근거(LimitSnapshot)가 필수입니다");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("REJECTED 전이는 거절 사유가 필수입니다");
        }
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

    /**
     * 일 1회 재심사 — 새 한도와 <b>그 근거를 함께</b> 갱신한다.
     *
     * <p>{@link #changeMasterLimit} 만으로는 부족한 이유가 근거다. 재산정은 어제와 다른 재원·평판으로
     * 계산한 결과인데 한도만 바꾸면 {@code limitSnapshot} 은 개설 시점 근거로 남아, 사후에 "왜 이
     * 한도였나"를 물었을 때 <b>서로 다른 심사를 가리키는 두 값</b>이 나온다. 영속 계층이
     * {@code screened_at} 을 스냅샷 변화로 판정하므로(변한 재심사만 시각 갱신) 그 어긋남은
     * 그대로 감사 기록의 거짓말이 된다.
     *
     * <p>{@link Builder} 로 새 인스턴스를 조립하는 우회를 쓰지 않는 이유: 빌더는 영속 계층의
     * 재구성 전용이라 상태 전이 가드를 통째로 건너뛴다. 그 길을 열면 CLOSED·REJECTED 계정도
     * 배치가 조용히 되살릴 수 있게 된다.
     *
     * <p>ACTIVE 만 허용한다(SUSPENDED 도 제외). 정지는 사람이 판단해 건 것이거나 재심사가 강등한
     * 결과인데, 배치가 자동으로 한도를 다시 얹으면 정지 사유가 해소됐는지 아무도 확인하지 않은 채
     * 여신이 되살아난다 — 복귀는 명시적인 {@link #resume} 을 거쳐야 한다.
     *
     * @param currentSubLimitSum 이미 배분된 Σ서브한도. 하향은 이 값을 하한으로 클램프된다.
     */
    public LimitChangeResult rescreen(BigDecimal newLimit, LimitSnapshot snapshot,
                                      BigDecimal currentSubLimitSum) {
        if (status != CardAccountStatus.ACTIVE) {
            throw new InvalidCardTransitionException(
                    "ACTIVE 카드계정만 재심사할 수 있습니다. 현재=" + status);
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("재심사는 한도 산정 근거(LimitSnapshot)가 필수입니다");
        }
        LimitChangeResult result = changeMasterLimit(newLimit, currentSubLimitSum);
        this.limitSnapshot = snapshot;
        return result;
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

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("한도는 음수일 수 없습니다: " + value);
        }
        return value;
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
