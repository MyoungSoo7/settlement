package github.lms.lemuel.card.domain;

import github.lms.lemuel.card.domain.exception.InvalidCardTransitionException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 임직원 카드 애그리거트(순수 POJO — 프레임워크 의존 0). CardAccount(마스터 한도) 아래에서
 * 서브한도를 배분받아 발급된다. 카드번호는 호출자가 이미 마스킹한 값만 받는다 — 원본 PAN 은
 * 이 도메인에 들어오지 않는다(PCI 스코프 축소).
 *
 * <pre>
 * ISSUED ⇄ SUSPENDED
 *   ↘        ↙
 *    CANCELED
 * </pre>
 *
 * <p>{@link #suspend()} 는 멱등이다 — organization-service 의 {@code member_removed} 이벤트가
 * at-least-once 로 재수신될 수 있어, 이미 SUSPENDED 인 카드를 다시 정지해도 조용히 무시해야 한다.
 * 반대로 {@link #cancel()} 은 멱등이 아니다 — 해지는 명시적 운영 행위라 중복 요청은 오류로 드러나야 한다.
 */
public class Card {

    private Long id;
    private final Long cardAccountId;
    private final Long holderUserId;
    private final String maskedCardNo;
    private BigDecimal subLimit;
    private CardStatus status;
    private long version;

    private Card(Builder b) {
        this.id = b.id;
        this.cardAccountId = Objects.requireNonNull(b.cardAccountId, "cardAccountId");
        this.holderUserId = Objects.requireNonNull(b.holderUserId, "holderUserId");
        this.maskedCardNo = requireMasked(b.maskedCardNo);
        this.subLimit = requireNonNegative(b.subLimit);
        this.status = Objects.requireNonNull(b.status, "status");
        this.version = b.version;
    }

    /** 카드 발급 — 기본 상태 ISSUED. */
    public static Card issue(Long cardAccountId, Long holderUserId, String maskedCardNo, BigDecimal subLimit) {
        return builder()
                .cardAccountId(cardAccountId)
                .holderUserId(holderUserId)
                .maskedCardNo(maskedCardNo)
                .subLimit(subLimit)
                .status(CardStatus.ISSUED)
                .build();
    }

    /** 서브한도 변경. CardAccount.assertCanIssue 로 마스터 한도와의 불변식은 응용 계층이 먼저 검증한다. */
    public void changeSubLimit(BigDecimal newSubLimit) {
        requireMutable();
        this.subLimit = requireNonNegative(newSubLimit);
    }

    /** 정지 — 멱등. 이미 SUSPENDED 면 아무 일도 하지 않는다(이벤트 재수신 대비). */
    public void suspend() {
        if (status == CardStatus.SUSPENDED) {
            return;
        }
        transitionTo(CardStatus.SUSPENDED);
    }

    public void resume() {
        transitionTo(CardStatus.ISSUED);
    }

    /** 해지 — 멱등이 아니다. 이미 CANCELED 인 카드를 다시 해지하면 예외로 드러난다. */
    public void cancel() {
        transitionTo(CardStatus.CANCELED);
    }

    // ── 상태 전이 가드 ──

    private void transitionTo(CardStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidCardTransitionException(status, target);
        }
        this.status = target;
    }

    private void requireMutable() {
        if (status == CardStatus.CANCELED) {
            throw new InvalidCardTransitionException("CANCELED 카드는 변경할 수 없습니다. 현재=" + status);
        }
    }

    private static BigDecimal requireNonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("서브한도는 음수일 수 없습니다: " + value);
        }
        return value;
    }

    private static String requireMasked(String maskedCardNo) {
        if (maskedCardNo == null || maskedCardNo.isBlank()) {
            throw new IllegalArgumentException("마스킹된 카드번호는 필수입니다");
        }
        return maskedCardNo;
    }

    // ── getter ──

    public Long getId() {
        return id;
    }

    public Long getCardAccountId() {
        return cardAccountId;
    }

    public Long getHolderUserId() {
        return holderUserId;
    }

    public String getMaskedCardNo() {
        return maskedCardNo;
    }

    public BigDecimal getSubLimit() {
        return subLimit;
    }

    public CardStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 정적 팩토리(issue)와 영속성 어댑터의 재구성이 공용하는 빌더. */
    public static class Builder {
        private Long id;
        private Long cardAccountId;
        private Long holderUserId;
        private String maskedCardNo;
        private BigDecimal subLimit;
        private CardStatus status;
        private long version;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder cardAccountId(Long v) {
            this.cardAccountId = v;
            return this;
        }

        public Builder holderUserId(Long v) {
            this.holderUserId = v;
            return this;
        }

        public Builder maskedCardNo(String v) {
            this.maskedCardNo = v;
            return this;
        }

        public Builder subLimit(BigDecimal v) {
            this.subLimit = v;
            return this;
        }

        public Builder status(CardStatus v) {
            this.status = v;
            return this;
        }

        public Builder version(long v) {
            this.version = v;
            return this;
        }

        public Card build() {
            return new Card(this);
        }
    }
}
